package com.sw1.backend.ai.agent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record AgentConversationMessage(
        @Pattern(regexp = "user|agent") String role,
        @NotBlank @Size(max = 4000) String text) {}
