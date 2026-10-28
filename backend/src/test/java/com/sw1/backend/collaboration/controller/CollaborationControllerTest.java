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
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CollaborationControllerTest {
    @Mock ProjectCollaborationAuthorizer authorizer;
    @Mock SimpMessagingTemplate messaging;

    @Test
    void rejectsEveryClientMutationIncludingForgedCommitAndPresence() {
        Principal principal = () -> "ana@example.com";
        when(authorizer.authorize(10L, principal)).thenReturn(new CollaborationIdentity(7L, "ana@example.com", "Ana"));
        var controller = new CollaborationController(authorizer, messaging);
        for (var type : CollaborationEventType.values()) {
            if (type == CollaborationEventType.PING) continue;
            var request = new CollaborationClientEvent(type, "client-1", Map.of("revision", 100), null);
            assertThrows(MessageDeliveryException.class, () -> controller.publish(10L, request, principal), type.name());
        }
        verifyNoInteractions(messaging);
    }

    @Test
    void presencePingUsesServerIdentityAndDoesNotRelayPayload() {
        Principal principal = () -> "ana@example.com";
        when(authorizer.authorize(10L, principal)).thenReturn(new CollaborationIdentity(7L, principal.getName(), "Ana"));
        new CollaborationController(authorizer, messaging).publish(10L,
                new CollaborationClientEvent(CollaborationEventType.PING, "forged", Map.of("document", "fake"), "hello"), principal);
        ArgumentCaptor<CollaborationEvent> event = ArgumentCaptor.forClass(CollaborationEvent.class);
        verify(messaging, times(1)).convertAndSend(eq("/topic/proyectos/10"), event.capture());
        assertEquals(7L, event.getValue().userId());
        assertNull(event.getValue().payload());
        assertNull(event.getValue().clientId());
    }
}
