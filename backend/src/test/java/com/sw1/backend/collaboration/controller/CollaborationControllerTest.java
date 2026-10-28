package com.sw1.backend.collaboration.controller;

import com.sw1.backend.collaboration.dto.CollaborationClientEvent;
import com.sw1.backend.collaboration.dto.CollaborationEvent;
import com.sw1.backend.collaboration.model.CollaborationEventType;
import com.sw1.backend.collaboration.security.CollaborationIdentity;
import com.sw1.backend.collaboration.security.ProjectCollaborationAuthorizer;
import com.sw1.backend.collaboration.service.ProjectPresenceService;
import com.sw1.backend.common.exception.AccesoDenegadoException;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CollaborationControllerTest {
    @Mock ProjectCollaborationAuthorizer authorizer;
    @Mock SimpMessagingTemplate messaging;

    @Test
    void rejectsReservedServerEventsFromClients() {
        Principal principal = () -> "ana@example.com";
        when(authorizer.authorize(10L, principal)).thenReturn(new CollaborationIdentity(7L, "ana@example.com", "Ana"));
        var controller = new CollaborationController(authorizer, messaging);
        for (var type : List.of(
                CollaborationEventType.USER_JOINED,
                CollaborationEventType.USER_LEFT,
                CollaborationEventType.DIAGRAM_SAVED,
                CollaborationEventType.DIAGRAM_COMMITTED)) {
            var request = new CollaborationClientEvent(type, "client-1", Map.of("revision", 100), null);
            assertThrows(MessageDeliveryException.class,
                    () -> controller.publish(10L, request, principal, "session-1"), type.name());
        }
        verifyNoInteractions(messaging);
    }

    @Test
    void presencePingUsesServerIdentityAndDoesNotRelayPayload() {
        Principal principal = () -> "ana@example.com";
        when(authorizer.authorize(10L, principal)).thenReturn(new CollaborationIdentity(7L, principal.getName(), "Ana"));
        new CollaborationController(authorizer, messaging).publish(10L,
                new CollaborationClientEvent(CollaborationEventType.PING, "forged", Map.of("document", "fake"), "hello"),
                principal, "session-1");
        ArgumentCaptor<CollaborationEvent> event = ArgumentCaptor.forClass(CollaborationEvent.class);
        verify(messaging, times(1)).convertAndSend(eq("/topic/proyectos/10"), event.capture());
        assertEquals(7L, event.getValue().userId());
        assertNull(event.getValue().payload());
        assertNull(event.getValue().clientId());
    }

    @Test
    void cursorEventsDifferentiateSameUserSessionsSanitizePayloadAndRateLimit() {
        Principal principal = () -> "ana@example.com";
        var identity = new CollaborationIdentity(7L, "ana@example.com", "Ana");
        when(authorizer.authorize(10L, principal)).thenReturn(identity);
        var presence = new ProjectPresenceService(messaging);
        var controller = new CollaborationController(authorizer, messaging, presence);

        controller.publish(10L, new CollaborationClientEvent(
                CollaborationEventType.CURSOR_MOVED, "tab-A",
                Map.of("x", 120.5, "y", -45.25, "sessionId", "forged-session", "extra", "ignored"), null),
                principal, "stomp-session-A");
        // Immediate duplicate burst on tab-A (< 25 ms) is silently rate-limited, while tab-B passes.
        controller.publish(10L, new CollaborationClientEvent(
                CollaborationEventType.CURSOR_MOVED, "tab-A",
                Map.of("x", 125.0, "y", -40.0), null),
                principal, "stomp-session-A");
        controller.publish(10L, new CollaborationClientEvent(
                CollaborationEventType.CURSOR_MOVED, "tab-B",
                Map.of("x", 300.0, "y", 210.0), null),
                principal, "stomp-session-B");

        var events = ArgumentCaptor.forClass(CollaborationEvent.class);
        verify(messaging, times(2)).convertAndSend(eq("/topic/proyectos/10"), events.capture());
        var first = events.getAllValues().get(0);
        var second = events.getAllValues().get(1);
        assertEquals("tab-A", first.clientId());
        assertEquals(Map.of("x", 120.5, "y", -45.25, "sessionId", "stomp-session-A"), first.payload());
        assertEquals("tab-B", second.clientId());
        assertEquals(Map.of("x", 300.0, "y", 210.0, "sessionId", "stomp-session-B"), second.payload());

        assertThrows(MessageDeliveryException.class, () -> controller.publish(10L,
                new CollaborationClientEvent(CollaborationEventType.CURSOR_MOVED, "tab-A",
                        Map.of("x", Double.NaN, "y", 0), null), principal, "stomp-session-A"));
        assertThrows(MessageDeliveryException.class, () -> controller.publish(10L,
                new CollaborationClientEvent(CollaborationEventType.CURSOR_MOVED, "tab-A",
                        Map.of("x", 250_000, "y", 0), null), principal, "stomp-session-A"));
    }

    @Test
    void disconnectEmitsCursorLeftForClosedTabWhileKeepingOtherTabOfSameUserAlive() {
        Principal principal = () -> "ana@example.com";
        var identity = new CollaborationIdentity(7L, "ana@example.com", "Ana");
        when(authorizer.authorize(10L, principal)).thenReturn(identity);
        var presence = new ProjectPresenceService(messaging);
        var controller = new CollaborationController(authorizer, messaging, presence);

        presence.join("stomp-A", "sub-1", 10L, identity);
        presence.join("stomp-B", "sub-2", 10L, identity);
        controller.publish(10L, new CollaborationClientEvent(
                CollaborationEventType.CURSOR_MOVED, "tab-A", Map.of("x", 10, "y", 20), null),
                principal, "stomp-A");
        controller.publish(10L, new CollaborationClientEvent(
                CollaborationEventType.CURSOR_MOVED, "tab-B", Map.of("x", 40, "y", 50), null),
                principal, "stomp-B");
        clearInvocations(messaging);

        presence.leaveSession("stomp-A");
        var afterFirstClose = ArgumentCaptor.forClass(CollaborationEvent.class);
        verify(messaging, times(1)).convertAndSend(eq("/topic/proyectos/10"), afterFirstClose.capture());
        assertEquals(CollaborationEventType.CURSOR_LEFT, afterFirstClose.getValue().type());
        assertEquals("tab-A", afterFirstClose.getValue().clientId());
        assertEquals(Map.of("sessionId", "stomp-A"), afterFirstClose.getValue().payload());

        clearInvocations(messaging);
        presence.leaveSession("stomp-B");
        var afterSecondClose = ArgumentCaptor.forClass(CollaborationEvent.class);
        verify(messaging, times(2)).convertAndSend(eq("/topic/proyectos/10"), afterSecondClose.capture());
        assertEquals(CollaborationEventType.CURSOR_LEFT, afterSecondClose.getAllValues().get(0).type());
        assertEquals(CollaborationEventType.USER_LEFT, afterSecondClose.getAllValues().get(1).type());
    }

    @Test
    void viewerCannotPublishLiveDiagramEventsWhileEditorBroadcastsValidatedEvents() {
        Principal viewer = () -> "viewer@example.com";
        doThrow(new AccesoDenegadoException("VIEWER")).when(authorizer).authorizeEditor(10L, viewer);
        var controller = new CollaborationController(authorizer, messaging);

        for (var type : List.of(
                CollaborationEventType.NODE_CREATED,
                CollaborationEventType.NODE_MOVED,
                CollaborationEventType.NODE_UPDATED,
                CollaborationEventType.NODE_DELETED,
                CollaborationEventType.EDGE_CREATED,
                CollaborationEventType.EDGE_UPDATED,
                CollaborationEventType.EDGE_DELETED,
                CollaborationEventType.DIAGRAM_BATCH_APPLIED)) {
            var request = new CollaborationClientEvent(
                    type, "client-viewer", Map.of("nodeId", "n1", "position", Map.of("x", 0, "y", 0)), null);
            assertThrows(MessageDeliveryException.class,
                    () -> controller.publish(10L, request, viewer, "session-v"), type.name());
        }
        verifyNoInteractions(messaging);

        Principal editor = () -> "editor@example.com";
        when(authorizer.authorizeEditor(10L, editor))
                .thenReturn(new CollaborationIdentity(9L, "editor@example.com", "José"));

        controller.publish(10L, new CollaborationClientEvent(
                CollaborationEventType.NODE_MOVED, "client-editor",
                Map.of("nodeId", "entity-1", "position", Map.of("x", 240, "y", 180), "dragging", true), null),
                editor, "session-e");

        Map<String, Object> validDoc = Map.of(
                "version", 2,
                "nodes", List.of(Map.of(
                        "id", "entity-1",
                        "type", "entity",
                        "position", Map.of("x", 240, "y", 180),
                        "data", Map.of("id", "entity-1", "name", "Producto", "kind", "CLASS",
                                "attributes", List.of(), "methods", List.of()))),
                "edges", List.of());
        controller.publish(10L, new CollaborationClientEvent(
                CollaborationEventType.DIAGRAM_BATCH_APPLIED, "client-editor",
                Map.of("document", validDoc), null), editor, "session-e");

        Map<String, Object> invalidDoc = Map.of(
                "version", 2,
                "nodes", List.of(
                        Map.of("id", "a", "type", "entity", "position", Map.of("x", 0, "y", 0),
                                "data", Map.of("id", "a", "name", "Duplicada", "attributes", List.of())),
                        Map.of("id", "b", "type", "entity", "position", Map.of("x", 10, "y", 10),
                                "data", Map.of("id", "b", "name", "Duplicada", "attributes", List.of()))),
                "edges", List.of());
        assertThrows(MessageDeliveryException.class, () -> controller.publish(10L,
                new CollaborationClientEvent(CollaborationEventType.DIAGRAM_BATCH_APPLIED, "client-editor",
                        Map.of("document", invalidDoc), null), editor, "session-e"));

        var delivered = ArgumentCaptor.forClass(CollaborationEvent.class);
        verify(messaging, times(2)).convertAndSend(eq("/topic/proyectos/10"), delivered.capture());
        assertEquals(CollaborationEventType.NODE_MOVED, delivered.getAllValues().get(0).type());
        assertEquals(9L, delivered.getAllValues().get(0).userId());
        assertEquals("José", delivered.getAllValues().get(0).name());
        assertEquals(true, delivered.getAllValues().get(0).payload().get("dragging"));
        assertEquals(CollaborationEventType.DIAGRAM_BATCH_APPLIED, delivered.getAllValues().get(1).type());
    }
}
