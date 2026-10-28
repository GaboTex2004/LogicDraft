package com.sw1.backend.collaboration.service;

import com.sw1.backend.collaboration.dto.CollaborationEvent;
import com.sw1.backend.collaboration.dto.ElementLockInfo;
import com.sw1.backend.collaboration.model.CollaborationEventType;
import com.sw1.backend.collaboration.security.CollaborationIdentity;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Authoritative per-element collaborative lease lock coordinator.
 *
 * <p>Scope note: matches {@code WebSocketConfig}'s single-instance STOMP broker
 * ({@code compose.deploy.yml}). Multi-instance horizontal scaling (Phase 6) requires
 * externalizing both the STOMP broker relay and this lease table to shared storage
 * (PostgreSQL lease table / advisory locks or Redis); it is intentionally not presented
 * as a distributed lock across separate JVMs.
 */
@Service
public class ProjectElementLockService implements DisposableBean {
    public static final Duration DEFAULT_LEASE_TTL = Duration.ofSeconds(30);
    private static final int MAX_ELEMENT_ID_LENGTH = 120;
    private static final int MAX_ELEMENTS_PER_BATCH = 200;
    private static final Pattern CLIENT_ID_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{1,100}$");

    private final SimpMessagingTemplate messagingTemplate;
    private final Clock clock;
    private final Duration leaseTtl;
    private final ScheduledExecutorService reaper;

    private final Map<Long, Object> projectMonitors = new ConcurrentHashMap<>();
    private final Map<Long, Map<String, ElementLockInfo>> projectLocks = new ConcurrentHashMap<>();
    private final Map<String, Set<SessionBinding>> sessionBindings = new ConcurrentHashMap<>();

    @Autowired
    public ProjectElementLockService(SimpMessagingTemplate messagingTemplate) {
        this(messagingTemplate, Clock.systemUTC(), DEFAULT_LEASE_TTL, true);
    }

    public ProjectElementLockService(
            SimpMessagingTemplate messagingTemplate,
            Clock clock,
            Duration leaseTtl,
            boolean startBackgroundReaper) {
        this.messagingTemplate = messagingTemplate;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.leaseTtl = Objects.requireNonNull(leaseTtl, "leaseTtl");
        if (startBackgroundReaper) {
            this.reaper = Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "logicdraft-element-lock-reaper");
                thread.setDaemon(true);
                return thread;
            });
            this.reaper.scheduleWithFixedDelay(this::safeEvictExpiredLocks, 2, 2, TimeUnit.SECONDS);
        } else {
            this.reaper = null;
        }
    }

    @Override
    public void destroy() {
        if (reaper != null) {
            reaper.shutdownNow();
        }
    }

    public List<ElementLockInfo> listActiveLocks(Long projectId) {
        Objects.requireNonNull(projectId, "projectId");
        List<ElementLockInfo> expired;
        List<ElementLockInfo> active;
        synchronized (monitorFor(projectId)) {
            Instant now = clock.instant();
            expired = purgeExpiredLocked(projectId, now);
            Map<String, ElementLockInfo> map = projectLocks.get(projectId);
            active = map == null
                    ? List.of()
                    : map.values().stream()
                            .sorted(Comparator.comparing(ElementLockInfo::elementId))
                            .toList();
        }
        broadcastExpired(expired);
        return active;
    }

    public List<ElementLockInfo> acquire(
            Long projectId,
            CollaborationIdentity identity,
            String clientId,
            String sessionId,
            Collection<String> elementIds) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(identity, "identity");
        String validClientId = validateClientId(clientId);
        TreeSet<String> sortedIds = normalizeElementIds(elementIds, true);

        if (sessionId != null && !sessionId.isBlank()) {
            recordBinding(sessionId, projectId, identity.userId(), validClientId);
        }

        List<ElementLockInfo> expired;
        List<ElementLockInfo> granted = new ArrayList<>(sortedIds.size());
        List<ElementLockInfo> newlyBroadcast = new ArrayList<>();

        synchronized (monitorFor(projectId)) {
            Instant now = clock.instant();
            expired = purgeExpiredLocked(projectId, now);
            Map<String, ElementLockInfo> locks =
                    projectLocks.computeIfAbsent(projectId, ignored -> new LinkedHashMap<>());

            // Phase 1: all-or-nothing check in deterministic lexicographical order
            for (String elementId : sortedIds) {
                ElementLockInfo existing = locks.get(elementId);
                if (existing != null && !existing.isOwnedBy(identity.userId(), validClientId)) {
                    // Broadcast any locks that just expired before throwing
                    broadcastExpired(expired);
                    throw new ElementLockedException(existing);
                }
            }

            // Phase 2: atomic grant / lease extension
            Instant expiresAt = now.plus(leaseTtl);
            String resolvedSessionId = resolveBoundSessionId(projectId, identity.userId(), validClientId, sessionId);
            for (String elementId : sortedIds) {
                ElementLockInfo previous = locks.get(elementId);
                Instant acquiredAt = previous != null ? previous.acquiredAt() : now;
                String effectiveSession = resolvedSessionId != null
                        ? resolvedSessionId
                        : (previous != null ? previous.sessionId() : null);
                ElementLockInfo updated = new ElementLockInfo(
                        projectId,
                        elementId,
                        identity.userId(),
                        identity.name(),
                        validClientId,
                        effectiveSession,
                        acquiredAt,
                        expiresAt);
                locks.put(elementId, updated);
                granted.add(updated);
                if (previous == null) {
                    newlyBroadcast.add(updated);
                }
            }
        }

        broadcastExpired(expired);
        if (!newlyBroadcast.isEmpty()) {
            broadcastLocked(projectId, identity, validClientId, newlyBroadcast);
        }
        return granted;
    }

    public List<ElementLockInfo> renew(
            Long projectId,
            CollaborationIdentity identity,
            String clientId,
            Collection<String> elementIds) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(identity, "identity");
        String validClientId = validateClientId(clientId);
        TreeSet<String> targetIds = (elementIds == null || elementIds.isEmpty())
                ? null
                : normalizeElementIds(elementIds, false);

        List<ElementLockInfo> expired;
        List<ElementLockInfo> renewed = new ArrayList<>();

        synchronized (monitorFor(projectId)) {
            Instant now = clock.instant();
            expired = purgeExpiredLocked(projectId, now);
            Map<String, ElementLockInfo> locks = projectLocks.get(projectId);
            if (locks != null) {
                Instant expiresAt = now.plus(leaseTtl);
                for (Map.Entry<String, ElementLockInfo> entry : locks.entrySet()) {
                    ElementLockInfo current = entry.getValue();
                    if (!current.isOwnedBy(identity.userId(), validClientId)) {
                        continue;
                    }
                    if (targetIds != null && !targetIds.contains(current.elementId())) {
                        continue;
                    }
                    ElementLockInfo updated = new ElementLockInfo(
                            current.projectId(),
                            current.elementId(),
                            current.userId(),
                            current.userName(),
                            current.clientId(),
                            current.sessionId(),
                            current.acquiredAt(),
                            expiresAt);
                    entry.setValue(updated);
                    renewed.add(updated);
                }
            }
        }

        broadcastExpired(expired);
        renewed.sort(Comparator.comparing(ElementLockInfo::elementId));
        return renewed;
    }

    public List<String> release(
            Long projectId,
            CollaborationIdentity identity,
            String clientId,
            Collection<String> elementIds) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(identity, "identity");
        String validClientId = validateClientId(clientId);
        TreeSet<String> targetIds = (elementIds == null || elementIds.isEmpty())
                ? null
                : normalizeElementIds(elementIds, false);

        List<ElementLockInfo> expired;
        List<ElementLockInfo> released = new ArrayList<>();

        synchronized (monitorFor(projectId)) {
            Instant now = clock.instant();
            expired = purgeExpiredLocked(projectId, now);
            Map<String, ElementLockInfo> locks = projectLocks.get(projectId);
            if (locks != null && !locks.isEmpty()) {
                if (targetIds != null) {
                    // Explicit release cannot target another collaborator's lock
                    for (String elementId : targetIds) {
                        ElementLockInfo current = locks.get(elementId);
                        if (current != null && !current.isOwnedBy(identity.userId(), validClientId)) {
                            broadcastExpired(expired);
                            throw new ElementLockedException(current);
                        }
                    }
                    for (String elementId : targetIds) {
                        ElementLockInfo removed = locks.remove(elementId);
                        if (removed != null) {
                            released.add(removed);
                        }
                    }
                } else {
                    var iterator = locks.entrySet().iterator();
                    while (iterator.hasNext()) {
                        ElementLockInfo current = iterator.next().getValue();
                        if (current.isOwnedBy(identity.userId(), validClientId)) {
                            iterator.remove();
                            released.add(current);
                        }
                    }
                }
            }
        }

        broadcastExpired(expired);
        if (!released.isEmpty()) {
            broadcastUnlocked(
                    projectId,
                    identity.userId(),
                    identity.name(),
                    validClientId,
                    released.getFirst().sessionId(),
                    released.stream().map(ElementLockInfo::elementId).toList());
        }
        return released.stream().map(ElementLockInfo::elementId).toList();
    }

    public void verifyCanModify(
            Long projectId,
            Collection<String> elementIds,
            Long userId,
            String clientId) {
        if (projectId == null || elementIds == null || elementIds.isEmpty()) {
            return;
        }
        List<ElementLockInfo> expired;
        synchronized (monitorFor(projectId)) {
            Instant now = clock.instant();
            expired = purgeExpiredLocked(projectId, now);
            Map<String, ElementLockInfo> locks = projectLocks.get(projectId);
            if (locks != null) {
                for (String elementId : new TreeSet<>(elementIds)) {
                    ElementLockInfo active = locks.get(elementId);
                    if (active != null && !active.isOwnedBy(userId, clientId)) {
                        broadcastExpired(expired);
                        throw new ElementLockedException(active);
                    }
                }
            }
        }
        broadcastExpired(expired);
    }

    public void bindSession(Long projectId, Long userId, String clientId, String sessionId) {
        if (projectId == null || userId == null || clientId == null || sessionId == null || sessionId.isBlank()) {
            return;
        }
        if (!CLIENT_ID_PATTERN.matcher(clientId).matches()) {
            return;
        }
        recordBinding(sessionId, projectId, userId, clientId);
        synchronized (monitorFor(projectId)) {
            Map<String, ElementLockInfo> locks = projectLocks.get(projectId);
            if (locks == null) return;
            for (Map.Entry<String, ElementLockInfo> entry : locks.entrySet()) {
                ElementLockInfo lock = entry.getValue();
                if (lock.isOwnedBy(userId, clientId) && !sessionId.equals(lock.sessionId())) {
                    entry.setValue(new ElementLockInfo(
                            lock.projectId(),
                            lock.elementId(),
                            lock.userId(),
                            lock.userName(),
                            lock.clientId(),
                            sessionId,
                            lock.acquiredAt(),
                            lock.expiresAt()));
                }
            }
        }
    }

    public void releaseSession(String sessionId, Long onlyProjectId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        Set<SessionBinding> bindings = sessionBindings.get(sessionId);
        Set<Long> candidateProjects = new TreeSet<>(projectLocks.keySet());
        if (bindings != null) {
            for (SessionBinding binding : bindings) {
                candidateProjects.add(binding.projectId());
            }
        }

        for (Long projectId : candidateProjects) {
            if (onlyProjectId != null && !onlyProjectId.equals(projectId)) {
                continue;
            }
            Set<SessionBinding> matchingBindings = bindings == null
                    ? Set.of()
                    : bindings.stream()
                            .filter(b -> b.projectId().equals(projectId))
                            .collect(java.util.stream.Collectors.toSet());

            List<ElementLockInfo> removedForSession = new ArrayList<>();
            synchronized (monitorFor(projectId)) {
                Map<String, ElementLockInfo> locks = projectLocks.get(projectId);
                if (locks != null) {
                    var iterator = locks.entrySet().iterator();
                    while (iterator.hasNext()) {
                        ElementLockInfo lock = iterator.next().getValue();
                        boolean sameSession = sessionId.equals(lock.sessionId());
                        boolean boundClient = matchingBindings.stream().anyMatch(
                                b -> lock.isOwnedBy(b.userId(), b.clientId()));
                        if (sameSession || boundClient) {
                            iterator.remove();
                            removedForSession.add(lock);
                        }
                    }
                }
            }

            if (bindings != null) {
                bindings.removeIf(b -> b.projectId().equals(projectId));
                if (bindings.isEmpty()) {
                    sessionBindings.remove(sessionId, bindings);
                }
            }

            broadcastGroupedUnlocks(removedForSession);
        }
    }

    public void evictExpiredLocks() {
        Instant now = clock.instant();
        List<ElementLockInfo> allExpired = new ArrayList<>();
        for (Long projectId : projectLocks.keySet()) {
            synchronized (monitorFor(projectId)) {
                allExpired.addAll(purgeExpiredLocked(projectId, now));
            }
        }
        broadcastExpired(allExpired);
    }

    private void safeEvictExpiredLocks() {
        try {
            evictExpiredLocks();
        } catch (RuntimeException ignored) {
            // Daemon reaper must never terminate on transient broker exception
        }
    }

    private List<ElementLockInfo> purgeExpiredLocked(Long projectId, Instant now) {
        Map<String, ElementLockInfo> locks = projectLocks.get(projectId);
        if (locks == null || locks.isEmpty()) {
            return List.of();
        }
        List<ElementLockInfo> expired = new ArrayList<>();
        var iterator = locks.entrySet().iterator();
        while (iterator.hasNext()) {
            ElementLockInfo lock = iterator.next().getValue();
            if (!lock.expiresAt().isAfter(now)) {
                iterator.remove();
                expired.add(lock);
            }
        }
        return expired;
    }

    private void recordBinding(String sessionId, Long projectId, Long userId, String clientId) {
        sessionBindings
                .computeIfAbsent(sessionId, ignored -> ConcurrentHashMap.newKeySet())
                .add(new SessionBinding(projectId, userId, clientId));
    }

    private String resolveBoundSessionId(Long projectId, Long userId, String clientId, String explicitSessionId) {
        if (explicitSessionId != null && !explicitSessionId.isBlank()) {
            return explicitSessionId;
        }
        for (Map.Entry<String, Set<SessionBinding>> entry : sessionBindings.entrySet()) {
            for (SessionBinding binding : entry.getValue()) {
                if (binding.projectId().equals(projectId)
                        && binding.userId().equals(userId)
                        && binding.clientId().equals(clientId)) {
                    return entry.getKey();
                }
            }
        }
        return null;
    }

    private Object monitorFor(Long projectId) {
        return projectMonitors.computeIfAbsent(projectId, ignored -> new Object());
    }

    private static String validateClientId(String clientId) {
        if (clientId == null || !CLIENT_ID_PATTERN.matcher(clientId).matches()) {
            throw new IllegalArgumentException("El clientId es inválido");
        }
        return clientId;
    }

    private static TreeSet<String> normalizeElementIds(Collection<String> elementIds, boolean requireNonEmpty) {
        if (elementIds == null || elementIds.isEmpty()) {
            if (requireNonEmpty) {
                throw new IllegalArgumentException("Se requiere al menos un elemento para bloquear");
            }
            return new TreeSet<>();
        }
        if (elementIds.size() > MAX_ELEMENTS_PER_BATCH) {
            throw new IllegalArgumentException("Se superó el número máximo de elementos por operación");
        }
        TreeSet<String> sorted = new TreeSet<>();
        for (String raw : elementIds) {
            if (raw == null || raw.isBlank() || raw.length() > MAX_ELEMENT_ID_LENGTH) {
                throw new IllegalArgumentException("Identificador de elemento inválido");
            }
            sorted.add(raw);
        }
        return sorted;
    }

    private void broadcastLocked(
            Long projectId,
            CollaborationIdentity identity,
            String clientId,
            List<ElementLockInfo> locks) {
        List<Map<String, Object>> serialized = locks.stream().map(ProjectElementLockService::toPayloadMap).toList();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("locks", serialized);
        if (locks.getFirst().sessionId() != null) {
            payload.put("sessionId", locks.getFirst().sessionId());
        }
        CollaborationEvent event = CollaborationEvent.create(
                CollaborationEventType.ELEMENT_LOCKED,
                identity.userId(),
                identity.name(),
                projectId,
                clientId,
                payload,
                null);
        messagingTemplate.convertAndSend("/topic/proyectos/" + projectId, event);
    }

    private void broadcastExpired(List<ElementLockInfo> expired) {
        if (expired == null || expired.isEmpty()) {
            return;
        }
        broadcastGroupedUnlocks(expired);
    }

    private void broadcastGroupedUnlocks(List<ElementLockInfo> unlocked) {
        if (unlocked == null || unlocked.isEmpty()) {
            return;
        }
        Map<String, List<ElementLockInfo>> byOwner = new LinkedHashMap<>();
        for (ElementLockInfo lock : unlocked) {
            String key = lock.projectId() + ":" + lock.userId() + ":" + lock.clientId();
            byOwner.computeIfAbsent(key, ignored -> new ArrayList<>()).add(lock);
        }
        for (List<ElementLockInfo> group : byOwner.values()) {
            ElementLockInfo sample = group.getFirst();
            broadcastUnlocked(
                    sample.projectId(),
                    sample.userId(),
                    sample.userName(),
                    sample.clientId(),
                    sample.sessionId(),
                    group.stream().map(ElementLockInfo::elementId).sorted().toList());
        }
    }

    private void broadcastUnlocked(
            Long projectId,
            Long userId,
            String userName,
            String clientId,
            String sessionId,
            List<String> elementIds) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("elementIds", elementIds);
        if (sessionId != null) {
            payload.put("sessionId", sessionId);
        }
        CollaborationEvent event = CollaborationEvent.create(
                CollaborationEventType.ELEMENT_UNLOCKED,
                userId,
                userName,
                projectId,
                clientId,
                payload,
                null);
        messagingTemplate.convertAndSend("/topic/proyectos/" + projectId, event);
    }

    public static Map<String, Object> toPayloadMap(ElementLockInfo lock) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("projectId", lock.projectId());
        item.put("elementId", lock.elementId());
        item.put("userId", lock.userId());
        item.put("userName", lock.userName());
        item.put("clientId", lock.clientId());
        if (lock.sessionId() != null) {
            item.put("sessionId", lock.sessionId());
        }
        item.put("acquiredAt", lock.acquiredAt().toString());
        item.put("expiresAt", lock.expiresAt().toString());
        return item;
    }

    private record SessionBinding(Long projectId, Long userId, String clientId) {}
}
