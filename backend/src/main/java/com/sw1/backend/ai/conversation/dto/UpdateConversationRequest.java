package com.sw1.backend.ai.conversation.dto;

import com.sw1.backend.ai.conversation.model.ConversationState;
import jakarta.validation.constraints.Size;

public record UpdateConversationRequest(
        @Size(max = 140, message = "El título no puede superar 140 caracteres")
        String titulo,
        ConversationState estado,
        @Size(max = 100)
        String clientId
) {
}
