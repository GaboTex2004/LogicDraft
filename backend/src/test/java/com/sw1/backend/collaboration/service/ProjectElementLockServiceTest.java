package com.sw1.backend.collaboration.service;

import com.sw1.backend.collaboration.controller.CollaborationController;
import com.sw1.backend.collaboration.controller.ProjectElementLockController;
import com.sw1.backend.collaboration.dto.CollaborationClientEvent;
import com.sw1.backend.collaboration.dto.CollaborationEvent;
import com.sw1.backend.collaboration.dto.ElementLockInfo;
import com.sw1.backend.collaboration.dto.ElementLockRequest;
import com.sw1.backend.collaboration.listener.CollaborationSessionListener;
import com.sw1.backend.collaboration.model.CollaborationEventType;
import com.sw1.backend.collaboration.security.CollaborationIdentity;
import com.sw1.backend.collaboration.security.ProjectCollaborationAuthorizer;
import com.sw1.backend.common.exception.AccesoDenegadoException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.security.Principal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class ProjectElementLockServiceTest {
    private static final Long PROJECT_ID = 10L;

    private MutableClock clock;
    private SimpMessagingTemplate messaging;
    private ProjectElementLockService lockService;
    private CollaborationIdentity gabriel;
    private CollaborationIdentity carlos;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-10-28T15:00:00Z"));
        messaging = mock(SimpMessagingTemplate.class);
        lockService = new ProjectElementLockService(messaging, clock, Duration.ofSeconds(30), false);
        gabriel = new CollaborationIdentity(1L, "gabriel@example.com", "Gabriel");
        carlos = new CollaborationIdentity(2L, "carlos@example.com", "Carlos");
    }

    @Test
    void acquireBlocksSameElementForOtherUserAllowsDifferentElementAndReleasesCleanly() {
        // 1. Usuario A adquiere un bloqueo
        List<ElementLockInfo> grantedA = lockService.acquire(
                PROJECT_ID, gabriel, "client-gabriel", "sess-g", List.of("ventas"));
        assertEquals(1, grantedA.size());
        assertEquals("Gabriel", grantedA.getFirst().userName());

        // 2. Usuario B no puede adquirir ni modificar el mismo elemento
        ElementLockedException blocked = assertThrows(ElementLockedException.class, () ->
                lockService.acquire(PROJECT_ID, carlos, "client-carlos", "sess-c", List.of("ventas")));
        assertEquals("Está siendo usado por Gabriel", blocked.getMessage());
        assertEquals("ventas", blocked.getElementId());
        assertEquals("Gabriel", blocked.getOwnerName());
        assertThrows(ElementLockedException.class, () ->
                lockService.verifyCanModify(PROJECT_ID, List.of("ventas"), carlos.userId(), "client-carlos"));

        // 3. Usuario B sí puede bloquear y editar otro elemento libre
        List<ElementLockInfo> grantedB = lockService.acquire(
                PROJECT_ID, carlos, "client-carlos", "sess-c", List.of("producto"));
        assertEquals(1, grantedB.size());
        assertDoesNotThrow(() ->
                lockService.verifyCanModify(PROJECT_ID, List.of("producto"), carlos.userId(), "client-carlos"));

        // 9. Un usuario no puede liberar el bloqueo de otro
        assertThrows(ElementLockedException.class, () ->
                lockService.release(PROJECT_ID, carlos, "client-carlos", List.of("ventas")));
        assertEquals(2, lockService.listActiveLocks(PROJECT_ID).size());

        // 4 & 5. El propietario libera su bloqueo y el elemento vuelve a quedar disponible
        List<String> released = lockService.release(
                PROJECT_ID, gabriel, "client-gabriel", List.of("ventas"));
        assertEquals(List.of("ventas"), released);

        List<ElementLockInfo> nowAvailable = lockService.acquire(
                PROJECT_ID, carlos, "client-carlos", "sess-c", List.of("ventas"));
        assertEquals("Carlos", nowAvailable.getFirst().userName());
    }

    @Test
    void leaseRenewalExtendsLifetimeAndExpiredOrDisconnectedLocksAreReleasedAutomatically() {
        lockService.acquire(PROJECT_ID, gabriel, "client-gabriel", "sess-g", List.of("ventas"));
        clock.advance(Duration.ofSeconds(20));

        // Heartbeat / renew extiende el lease otros 30 s
        List<ElementLockInfo> renewed = lockService.renew(
                PROJECT_ID, gabriel, "client-gabriel", List.of("ventas"));
        assertEquals(1, renewed.size());

        clock.advance(Duration.ofSeconds(20));
        assertEquals(1, lockService.listActiveLocks(PROJECT_ID).size());

        // 6a. Expiración automática tras vencer el lease sin renovación
        clock.advance(Duration.ofSeconds(11));
        lockService.evictExpiredLocks();
        assertTrue(lockService.listActiveLocks(PROJECT_ID).isEmpty());
        assertDoesNotThrow(() ->
                lockService.acquire(PROJECT_ID, carlos, "client-carlos", "sess-c", List.of("ventas")));

        // 6b. Liberación inmediata al desconectarse la sesión WebSocket/STOMP
        var presence = new ProjectPresenceService(messaging);
        var authorizer = mock(ProjectCollaborationAuthorizer.class);
        var listener = new CollaborationSessionListener(authorizer, presence, lockService);

        var headers = SimpMessageHeaderAccessor.create(SimpMessageType.DISCONNECT);
        headers.setSessionId("sess-c");
        var message = MessageBuilder.createMessage(new byte[0], headers.getMessageHeaders());
        listener.onDisconnect(new SessionDisconnectEvent(this, message, "sess-c", CloseStatus.NORMAL));

        assertTrue(lockService.listActiveLocks(PROJECT_ID).isEmpty());
        ArgumentCaptor<CollaborationEvent> events = ArgumentCaptor.forClass(CollaborationEvent.class);
        verify(messaging, atLeastOnce()).convertAndSend(eq("/topic/proyectos/10"), events.capture());
        assertTrue(events.getAllValues().stream()
                .anyMatch(e -> e.type() == CollaborationEventType.ELEMENT_UNLOCKED));
    }

    @Test
    void viewerCannotAcquireLocksAndClientsCannotForgeLockEventsOverStomp() {
        var authorizer = mock(ProjectCollaborationAuthorizer.class);
        Principal viewerPrincipal = () -> "viewer@example.com";
        when(authorizer.authorize(PROJECT_ID, viewerPrincipal))
                .thenReturn(new CollaborationIdentity(3L, "viewer@example.com", "Viewer"));
        when(authorizer.authorizeEditor(PROJECT_ID, viewerPrincipal))
                .thenThrow(new AccesoDenegadoException("No tienes permiso para modificar este proyecto"));

        var restController = new ProjectElementLockController(authorizer, lockService);

        // 7. VIEWER puede consultar bloqueos activos pero nunca adquirirlos
        assertEquals(200, restController.listar(PROJECT_ID, viewerPrincipal).getStatusCode().value());
        assertThrows(AccesoDenegadoException.class, () ->
                restController.adquirir(
                        PROJECT_ID,
                        new ElementLockRequest("client-viewer", List.of("ventas")),
                        viewerPrincipal));
        assertTrue(lockService.listActiveLocks(PROJECT_ID).isEmpty());

        // Clientes STOMP no pueden falsificar ELEMENT_LOCKED ni ELEMENT_UNLOCKED
        var stompController = new CollaborationController(
                authorizer, messaging, new ProjectPresenceService(messaging), lockService);
        for (var reserved : List.of(CollaborationEventType.ELEMENT_LOCKED, CollaborationEventType.ELEMENT_UNLOCKED)) {
            assertThrows(MessageDeliveryException.class, () ->
                    stompController.publish(
                            PROJECT_ID,
                            new CollaborationClientEvent(reserved, "client-viewer", Map.of(), null),
                            viewerPrincipal,
                            "sess-v"));
        }
    }

    @Test
    void simultaneousAcquisitionNeverGrantsDoubleOwnershipAndMultiElementLockIsAtomic() throws Exception {
        // 8. Dos adquisiciones simultáneas no consiguen doble propiedad (incluyendo dos sesiones del mismo usuario)
        var barrier = new CyclicBarrier(2);
        var winners = new AtomicInteger();
        var conflicts = new AtomicInteger();

        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                try {
                    lockService.acquire(PROJECT_ID, gabriel, "tab-1", "sess-1", List.of("ventas"));
                    winners.incrementAndGet();
                } catch (ElementLockedException conflict) {
                    conflicts.incrementAndGet();
                }
                return null;
            });
            var second = pool.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                try {
                    lockService.acquire(PROJECT_ID, carlos, "tab-2", "sess-2", List.of("ventas"));
                    winners.incrementAndGet();
                } catch (ElementLockedException conflict) {
                    conflicts.incrementAndGet();
                }
                return null;
            });
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
        }

        assertEquals(1, winners.get());
        assertEquals(1, conflicts.get());
        assertEquals(1, lockService.listActiveLocks(PROJECT_ID).size());

        // Dos pestañas simultáneas del MISMO usuario tampoco comparten bloqueo implícito
        String winningClient = lockService.listActiveLocks(PROJECT_ID).getFirst().clientId();
        CollaborationIdentity winningUser = "tab-1".equals(winningClient) ? gabriel : carlos;
        assertThrows(ElementLockedException.class, () ->
                lockService.acquire(PROJECT_ID, winningUser, "other-tab-same-user", "sess-other", List.of("ventas")));

        // 10. Operación multi-elemento (ej. conversión N:M -> clase asociativa: [edge, source, target]) es atómica
        assertThrows(ElementLockedException.class, () ->
                lockService.acquire(
                        PROJECT_ID,
                        "tab-1".equals(winningClient) ? carlos : gabriel,
                        "multi-client",
                        "sess-multi",
                        List.of("producto", "rel-nm", "ventas")));
        // Ni "producto" ni "rel-nm" deben haber quedado bloqueados parcialmente
        assertEquals(1, lockService.listActiveLocks(PROJECT_ID).size());
        assertEquals("ventas", lockService.listActiveLocks(PROJECT_ID).getFirst().elementId());
    }

    @Test
    void stompControllerDropsSpoofedLivePreviewTargetingElementLockedByAnotherSession() {
        var authorizer = mock(ProjectCollaborationAuthorizer.class);
        Principal gabrielPrincipal = () -> "gabriel@example.com";
        Principal carlosPrincipal = () -> "carlos@example.com";
        when(authorizer.authorizeEditor(PROJECT_ID, gabrielPrincipal)).thenReturn(gabriel);
        when(authorizer.authorizeEditor(PROJECT_ID, carlosPrincipal)).thenReturn(carlos);

        lockService.acquire(PROJECT_ID, gabriel, "client-gabriel", "sess-g", List.of("ventas"));
        reset(messaging);

        var controller = new CollaborationController(
                authorizer, messaging, new ProjectPresenceService(messaging), lockService);

        // Carlos intenta emitir NODE_MOVED sobre "ventas" (bloqueado por Gabriel) -> descartado
        controller.publish(
                PROJECT_ID,
                new CollaborationClientEvent(
                        CollaborationEventType.NODE_MOVED,
                        "client-carlos",
                        Map.of("nodeId", "ventas", "position", Map.of("x", 300, "y", 200), "dragging", true),
                        null),
                carlosPrincipal,
                "sess-c");
        verifyNoInteractions(messaging);

        // Carlos emite NODE_MOVED sobre "producto" (libre) -> retransmitido normalmente
        controller.publish(
                PROJECT_ID,
                new CollaborationClientEvent(
                        CollaborationEventType.NODE_MOVED,
                        "client-carlos",
                        Map.of("nodeId", "producto", "position", Map.of("x", 500, "y", 200), "dragging", true),
                        null),
                carlosPrincipal,
                "sess-c");
        verify(messaging, times(1)).convertAndSend(eq("/topic/proyectos/10"), any(CollaborationEvent.class));
    }

    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> current;

        MutableClock(Instant initial) {
            this.current = new AtomicReference<>(initial);
        }

        void advance(Duration delta) {
            current.updateAndGet(now -> now.plus(delta));
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return current.get();
        }
    }
}
