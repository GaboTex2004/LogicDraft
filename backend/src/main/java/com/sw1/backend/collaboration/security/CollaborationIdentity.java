package com.sw1.backend.collaboration.security;

public record CollaborationIdentity(Long userId, String email, String name) {
}
