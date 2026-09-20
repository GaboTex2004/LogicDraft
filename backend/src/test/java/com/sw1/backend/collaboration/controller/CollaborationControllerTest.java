package com.sw1.backend.collaboration.controller;

import com.sw1.backend.collaboration.dto.CollaborationClientEvent;
import com.sw1.backend.collaboration.dto.CollaborationEvent;
import com.sw1.backend.collaboration.model.CollaborationEventType;
import com.sw1.backend.collaboration.security.CollaborationIdentity;
import com.sw1.backend.collaboration.security.ProjectCollaborationAuthorizer;
import java.security.Principal;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CollaborationControllerTest {
    @Mock ProjectCollaborationAuthorizer authorizer;
    @Mock SimpMessagingTemplate messaging;

    @Test
    void associationConversionIsPublishedAsOneEditorAuthorizedBatch() {
        Principal principal = () -> "ana@example.com";
        when(authorizer.authorize(10L, principal)).thenReturn(new CollaborationIdentity(7L, "ana@example.com", "Ana"));
        when(authorizer.authorizeEditor(10L, principal)).thenReturn(new CollaborationIdentity(7L, "ana@example.com", "Ana"));
        Map<String, Object> payload = Map.of("document", Map.of("version", 1, "nodes", java.util.List.of(), "edges", java.util.List.of()));
        var request = new CollaborationClientEvent(
                CollaborationEventType.DIAGRAM_BATCH_APPLIED, "client-1", payload, null);

        new CollaborationController(authorizer, messaging).publish(10L, request, principal);

        ArgumentCaptor<CollaborationEvent> event = ArgumentCaptor.forClass(CollaborationEvent.class);
        verify(messaging, times(1)).convertAndSend(eq("/topic/proyectos/10"), event.capture());
        assertEquals(CollaborationEventType.DIAGRAM_BATCH_APPLIED, event.getValue().type());
        assertEquals(payload, event.getValue().payload());
        verify(authorizer).authorizeEditor(10L, principal);
    }
}
