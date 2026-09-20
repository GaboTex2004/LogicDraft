package com.sw1.backend.collaboration.controller;

import com.sw1.backend.collaboration.dto.CollaborationClientEvent;
import com.sw1.backend.collaboration.dto.CollaborationEvent;
import com.sw1.backend.collaboration.model.CollaborationEventType;
import com.sw1.backend.collaboration.security.CollaborationIdentity;
import com.sw1.backend.collaboration.security.ProjectCollaborationAuthorizer;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;

import java.security.Principal;

@Controller
public class CollaborationController {
    private final ProjectCollaborationAuthorizer authorizer;
    private final SimpMessagingTemplate messagingTemplate;

    public CollaborationController(
            ProjectCollaborationAuthorizer authorizer,
            SimpMessagingTemplate messagingTemplate) {
        this.authorizer = authorizer;
        this.messagingTemplate = messagingTemplate;
    }

    @MessageMapping("/proyectos/{projectId}/eventos")
    public void publish(
            @DestinationVariable Long projectId,
            @Payload CollaborationClientEvent request,
            Principal principal) {
        CollaborationIdentity identity = authorizer.authorize(projectId, principal);
        if (request == null || request.type() == null || !isClientEvent(request.type())) {
            throw new MessageDeliveryException("Tipo de evento de colaboración no permitido");
        }
        if (request.type() != CollaborationEventType.PING) {
            authorizer.authorizeEditor(projectId, principal);
            if (request.clientId() == null || request.clientId().isBlank()
                    || request.clientId().length() > 100 || request.payload() == null) {
                throw new MessageDeliveryException("El evento requiere clientId y payload válidos");
            }
        }
        CollaborationEvent event = CollaborationEvent.create(
                request.type(), identity.userId(), identity.name(), projectId,
                request.clientId(), request.payload(), request.message());
        messagingTemplate.convertAndSend("/topic/proyectos/" + projectId, event);
    }

    private boolean isClientEvent(CollaborationEventType type) {
        return switch (type) {
            case PING, NODE_CREATED, NODE_MOVED, NODE_UPDATED, NODE_DELETED,
                    EDGE_CREATED, EDGE_UPDATED, EDGE_DELETED, DIAGRAM_SAVED -> true;
            case DIAGRAM_BATCH_APPLIED -> true;
            case USER_JOINED, USER_LEFT -> false;
        };
    }
}
