package com.sw1.backend.collaboration.security;

import com.sw1.backend.collaboration.service.ProjectPresenceService;
import java.util.regex.Pattern;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.stereotype.Component;

/** Re-check membership on delivery: an old subscription is not permanent permission. */
@Component
public class ProjectSubscriptionInterceptor implements ChannelInterceptor {
    private static final Pattern TOPIC = Pattern.compile("^/topic/proyectos/(\\d+)$");
    private final ObjectProvider<ProjectPresenceService> presence;
    private final ObjectProvider<ProjectCollaborationAuthorizer> authorizer;

    public ProjectSubscriptionInterceptor(ObjectProvider<ProjectPresenceService> presence,
                                          ObjectProvider<ProjectCollaborationAuthorizer> authorizer) {
        this.presence = presence;
        this.authorizer = authorizer;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        var headers = SimpMessageHeaderAccessor.wrap(message);
        if (headers.getMessageType() != SimpMessageType.MESSAGE) return message;
        String destination = headers.getDestination();
        var matcher = TOPIC.matcher(destination == null ? "" : destination);
        if (!matcher.matches()) return null;
        try {
            Long projectId = Long.valueOf(matcher.group(1));
            var identity = presence.getObject().subscriber(headers.getSessionId(), headers.getSubscriptionId(), projectId);
            if (identity == null) return null;
            authorizer.getObject().authorize(projectId, () -> identity.email());
            return message;
        } catch (RuntimeException denied) {
            return null;
        }
    }
}
