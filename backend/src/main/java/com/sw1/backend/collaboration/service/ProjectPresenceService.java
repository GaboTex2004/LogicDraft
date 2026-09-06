package com.sw1.backend.collaboration.service;

import com.sw1.backend.collaboration.dto.CollaborationEvent;
import com.sw1.backend.collaboration.model.CollaborationEventType;
import com.sw1.backend.collaboration.security.CollaborationIdentity;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ProjectPresenceService {
    private final SimpMessagingTemplate messagingTemplate;
    private final Map<String, PresenceConnection> connections = new ConcurrentHashMap<>();

    public ProjectPresenceService(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    public void join(String sessionId, String subscriptionId, Long projectId, CollaborationIdentity identity) {
        String connectionId = sessionId + ":" + subscriptionId;
        connections.put(connectionId, new PresenceConnection(sessionId, projectId, identity));

        connectedUsers(projectId).forEach(user -> send(CollaborationEvent.create(
                CollaborationEventType.USER_JOINED,
                user.userId(), user.name(), projectId, null, null, null)));
    }

    public void leaveSession(String sessionId) {
        List<PresenceConnection> leaving = connections.entrySet().stream()
                .filter(entry -> entry.getValue().sessionId().equals(sessionId))
                .map(Map.Entry::getValue)
                .toList();
        connections.entrySet().removeIf(entry -> entry.getValue().sessionId().equals(sessionId));

        leaving.stream()
                .distinct()
                .filter(connection -> !isStillConnected(connection.projectId(), connection.identity().userId()))
                .forEach(connection -> send(CollaborationEvent.create(
                        CollaborationEventType.USER_LEFT,
                        connection.identity().userId(), connection.identity().name(),
                        connection.projectId(), null, null, null)));
    }

    private List<CollaborationIdentity> connectedUsers(Long projectId) {
        return connections.values().stream()
                .filter(connection -> connection.projectId().equals(projectId))
                .map(PresenceConnection::identity)
                .distinct()
                .sorted(Comparator.comparing(CollaborationIdentity::userId))
                .toList();
    }

    private boolean isStillConnected(Long projectId, Long userId) {
        return connections.values().stream().anyMatch(connection ->
                connection.projectId().equals(projectId)
                        && connection.identity().userId().equals(userId));
    }

    private void send(CollaborationEvent event) {
        messagingTemplate.convertAndSend("/topic/proyectos/" + event.projectId(), event);
    }

    private record PresenceConnection(
            String sessionId,
            Long projectId,
            CollaborationIdentity identity) {
    }
}
