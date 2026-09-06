package com.sw1.backend.ai.agent.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;

public record AgentEventInput(
        @NotNull AgentEventType type,
        @Size(max = 100) String nodeId,
        @Size(max = 100) String edgeId,
        @NotNull Instant timestamp) {}
