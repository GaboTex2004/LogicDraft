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
        if (request == null || request.type() != CollaborationEventType.PING) {
            throw new MessageDeliveryException("Los cambios del diagrama requieren guardado REST validado con baseRevision");
        }
        if (request.message() != null && request.message().length() > 200) {
            throw new MessageDeliveryException("PING demasiado largo");
        }
        CollaborationEvent event = CollaborationEvent.create(
                request.type(), identity.userId(), identity.name(), projectId,
                null, null, request.message());
        messagingTemplate.convertAndSend("/topic/proyectos/" + projectId, event);
    }
}
