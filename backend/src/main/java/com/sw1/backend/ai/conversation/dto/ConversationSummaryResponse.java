package com.sw1.backend.ai.conversation.dto;

import com.sw1.backend.ai.conversation.model.ConversationState;

import java.time.LocalDateTime;

public record ConversationSummaryResponse(
        Long id,
        Long projectId,
        String titulo,
        ConversationState estado,
        Long creadoPorId,
        String creadoPorNombre,
        LocalDateTime fechaCreacion,
        LocalDateTime fechaActualizacion
) {
}
