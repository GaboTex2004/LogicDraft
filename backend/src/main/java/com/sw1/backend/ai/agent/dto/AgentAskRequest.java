package com.sw1.backend.ai.agent.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

public record AgentAskRequest(
        @NotBlank @Size(max = 4000) String message,
        @Size(max = 100) String selectedNodeId,
        @Size(max = 100) String selectedEdgeId,
        @NotNull @Size(max = 25) List<@Valid AgentEventInput> recentEvents,
        @Size(max = 10) List<@Valid AgentConversationMessage> conversation) {
    public AgentAskRequest(String message, String selectedNodeId, String selectedEdgeId,
                           List<AgentEventInput> recentEvents) {
        this(message, selectedNodeId, selectedEdgeId, recentEvents, List.of());
    }
}
