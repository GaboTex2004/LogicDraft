package com.sw1.backend.diagrama.service;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bounded per-project ring buffer of confirmed diagram snapshots ({@code revision -> contenido}).
 *
 * <p>Enables server-authoritative three-way merges when two collaborators save disjoint
 * UML changes starting from the same confirmed {@code baseRevision}, without trusting
 * client-supplied base snapshots or altering the PostgreSQL schema.
 */
@Component
public class DiagramRevisionHistory {
    static final int MAX_REVISIONS_PER_PROJECT = 32;

    private final Map<Long, NavigableMap<Long, Map<String, Object>>> historyByProject =
            new ConcurrentHashMap<>();

    public void record(Long projectId, Long revision, Map<String, Object> contenido) {
        if (projectId == null || revision == null || revision < 0 || contenido == null) {
            return;
        }
        Map<String, Object> snapshot = DiagramThreeWayMerge.deepCopyMap(contenido);
        NavigableMap<Long, Map<String, Object>> projectHistory =
                historyByProject.computeIfAbsent(projectId, ignored -> new TreeMap<>());
        synchronized (projectHistory) {
            projectHistory.put(revision, snapshot);
            while (projectHistory.size() > MAX_REVISIONS_PER_PROJECT) {
                projectHistory.pollFirstEntry();
            }
        }
    }

    public Optional<Map<String, Object>> find(Long projectId, Long revision) {
        if (projectId == null || revision == null) {
            return Optional.empty();
        }
        NavigableMap<Long, Map<String, Object>> projectHistory = historyByProject.get(projectId);
        if (projectHistory == null) {
            return Optional.empty();
        }
        synchronized (projectHistory) {
            return Optional.ofNullable(projectHistory.get(revision));
        }
    }
}
