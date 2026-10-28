package com.sw1.backend.ai.conversation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateConversationRequest(
        @NotBlank(message = "El título de la conversación es obligatorio")
        @Size(max = 140, message = "El título no puede superar 140 caracteres")
        String titulo,
        @Size(max = 100)
        String clientId
) {
}
