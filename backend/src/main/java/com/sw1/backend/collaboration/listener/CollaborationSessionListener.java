package com.sw1.backend.collaboration.listener;

import com.sw1.backend.collaboration.security.CollaborationIdentity;
import com.sw1.backend.collaboration.security.ProjectCollaborationAuthorizer;
import com.sw1.backend.collaboration.service.ProjectPresenceService;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;
import org.springframework.web.socket.messaging.SessionSubscribeEvent;

import java.security.Principal;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class CollaborationSessionListener {
    private static final Pattern PROJECT_TOPIC = Pattern.compile("^/topic/proyectos/(\\d+)$");
    private final ProjectCollaborationAuthorizer authorizer;
    private final ProjectPresenceService presenceService;

    public CollaborationSessionListener(
            ProjectCollaborationAuthorizer authorizer,
            ProjectPresenceService presenceService) {
        this.authorizer = authorizer;
        this.presenceService = presenceService;
    }

    @EventListener
    public void onSubscribe(SessionSubscribeEvent event) {
        SimpMessageHeaderAccessor accessor = SimpMessageHeaderAccessor.wrap(event.getMessage());
        String destination = accessor.getDestination();
        String sessionId = accessor.getSessionId();
        String subscriptionId = accessor.getSubscriptionId();
        Principal principal = event.getUser();
        Matcher matcher = destination == null ? PROJECT_TOPIC.matcher("") : PROJECT_TOPIC.matcher(destination);
        if (!matcher.matches() || sessionId == null || subscriptionId == null || principal == null) return;

        Long projectId = Long.valueOf(matcher.group(1));
        CollaborationIdentity identity = authorizer.authorize(projectId, principal);
        presenceService.join(sessionId, subscriptionId, projectId, identity);
    }

    @EventListener
    public void onDisconnect(SessionDisconnectEvent event) {
        presenceService.leaveSession(event.getSessionId());
    }
}
