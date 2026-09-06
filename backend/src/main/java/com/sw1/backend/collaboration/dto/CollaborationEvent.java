package com.sw1.backend.collaboration.dto;

import com.sw1.backend.collaboration.model.CollaborationEventType;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record CollaborationEvent(
        String eventId,
        CollaborationEventType type,
        Long userId,
        String name,
        Long projectId,
        String clientId,
        Instant timestamp,
        Map<String, Object> payload,
        String message) {

    public static CollaborationEvent create(
            CollaborationEventType type,
            Long userId,
            String name,
            Long projectId,
            String clientId,
            Map<String, Object> payload,
            String message) {
        return new CollaborationEvent(
                UUID.randomUUID().toString(), type, userId, name, projectId,
                clientId, Instant.now(), payload, message);
    }
}
