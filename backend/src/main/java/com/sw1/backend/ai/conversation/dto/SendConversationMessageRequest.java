package com.sw1.backend.ai.conversation.dto;

import com.sw1.backend.ai.agent.dto.AgentEventInput;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

public record SendConversationMessageRequest(
        @NotBlank(message = "El mensaje no puede estar vacío")
        @Size(max = 4000, message = "El mensaje no puede superar 4000 caracteres")
        String contenido,
        @Size(max = 100)
        String clientMessageId,
        boolean invokeAgent,
        @Size(max = 120)
        String selectedNodeId,
        @Size(max = 120)
        String selectedEdgeId,
        @Size(max = 25)
        List<@Valid AgentEventInput> recentEvents,
        @Size(max = 100)
        String clientId
) {
    public SendConversationMessageRequest {
        recentEvents = recentEvents == null ? List.of() : List.copyOf(recentEvents);
    }
}
