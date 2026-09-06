package com.sw1.backend.collaboration.dto;

import com.sw1.backend.collaboration.model.CollaborationEventType;

import java.util.Map;

public record CollaborationClientEvent(
        CollaborationEventType type,
        String clientId,
        Map<String, Object> payload,
        String message) {
}
