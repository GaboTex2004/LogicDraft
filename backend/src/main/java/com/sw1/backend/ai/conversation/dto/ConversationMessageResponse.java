package com.sw1.backend.ai.conversation.dto;

import com.sw1.backend.ai.conversation.model.ConversationMessageStatus;
import com.sw1.backend.ai.conversation.model.MessageAuthorType;

import java.time.LocalDateTime;

public record ConversationMessageResponse(
        Long id,
        Long conversationId,
        Long projectId,
        MessageAuthorType autorTipo,
        Long autorUsuarioId,
        String autorNombre,
        Long solicitadoPorId,
        String solicitadoPorNombre,
        String contenido,
        ConversationMessageStatus estado,
        String clientMessageId,
        Long mensajeOrigenId,
        LocalDateTime fechaCreacion,
        SharedProposalResponse proposal
) {
}
