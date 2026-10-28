package com.sw1.backend.ai.conversation.dto;

import com.sw1.backend.ai.conversation.model.AgentProposalState;
import com.sw1.backend.ai.diagram.dto.DiagramOperation;

import java.time.LocalDateTime;
import java.util.List;

public record SharedProposalResponse(
        Long id,
        Long conversationId,
        Long messageId,
        Long projectId,
        Long solicitadoPorId,
        String solicitadoPorNombre,
        AgentProposalState estado,
        Long baseRevision,
        Long aplicadaRevision,
        List<DiagramOperation> operations,
        List<String> affectedElementIds,
        String resumen,
        Long decididoPorId,
        String decididoPorNombre,
        LocalDateTime fechaDecision,
        String resultadoAplicacion,
        LocalDateTime fechaCreacion
) {
    public SharedProposalResponse {
        operations = operations == null ? List.of() : List.copyOf(operations);
        affectedElementIds = affectedElementIds == null ? List.of() : List.copyOf(affectedElementIds);
    }
}
