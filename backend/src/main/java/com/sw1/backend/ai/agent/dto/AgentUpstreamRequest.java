package com.sw1.backend.ai.agent.dto;

import java.util.List;

public record AgentUpstreamRequest(String message, AgentProjectContext context,
                                   List<AgentConversationMessage> conversation) {
    public AgentUpstreamRequest(String message, AgentProjectContext context) {
        this(message, context, List.of());
    }
}
