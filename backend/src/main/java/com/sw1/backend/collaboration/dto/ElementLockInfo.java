package com.sw1.backend.collaboration.dto;

import java.time.Instant;

public record ElementLockInfo(
        Long projectId,
        String elementId,
        Long userId,
        String userName,
        String clientId,
        String sessionId,
        Instant acquiredAt,
        Instant expiresAt
) {
    public boolean isOwnedBy(Long candidateUserId, String candidateClientId) {
        if (candidateUserId == null || !userId.equals(candidateUserId)) {
            return false;
        }
        if (candidateClientId == null) {
            return true;
        }
        return clientId.equals(candidateClientId);
    }
}
