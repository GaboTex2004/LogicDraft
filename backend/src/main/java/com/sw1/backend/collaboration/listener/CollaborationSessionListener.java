package com.sw1.backend.collaboration.listener;

import com.sw1.backend.collaboration.security.CollaborationIdentity;
import com.sw1.backend.collaboration.security.ProjectCollaborationAuthorizer;
import com.sw1.backend.collaboration.service.ProjectElementLockService;
import com.sw1.backend.collaboration.service.ProjectPresenceService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;
import org.springframework.web.socket.messaging.SessionSubscribeEvent;
import org.springframework.web.socket.messaging.SessionUnsubscribeEvent;

import java.security.Principal;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class CollaborationSessionListener {
    private static final Pattern PROJECT_TOPIC = Pattern.compile("^/topic/proyectos/(\\d+)$");
    private final ProjectCollaborationAuthorizer authorizer;
    private final ProjectPresenceService presenceService;
    private final ProjectElementLockService lockService;

    @Autowired
    public CollaborationSessionListener(
            ProjectCollaborationAuthorizer authorizer,
            ProjectPresenceService presenceService,
            ProjectElementLockService lockService) {
        this.authorizer = authorizer;
        this.presenceService = presenceService;
        this.lockService = lockService;
    }

    public CollaborationSessionListener(
            ProjectCollaborationAuthorizer authorizer,
            ProjectPresenceService presenceService) {
        this(authorizer, presenceService, null);
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
    public void onUnsubscribe(SessionUnsubscribeEvent event) {
        SimpMessageHeaderAccessor accessor = SimpMessageHeaderAccessor.wrap(event.getMessage());
        if (accessor.getSessionId() != null && accessor.getSubscriptionId() != null) {
            Long leftProjectId = presenceService.leaveSubscription(accessor.getSessionId(), accessor.getSubscriptionId());
            if (leftProjectId != null && lockService != null) {
                lockService.releaseSession(accessor.getSessionId(), leftProjectId);
            }
        }
    }

    @EventListener
    public void onDisconnect(SessionDisconnectEvent event) {
        presenceService.leaveSession(event.getSessionId());
        if (lockService != null) {
            lockService.releaseSession(event.getSessionId(), null);
        }
    }
}
