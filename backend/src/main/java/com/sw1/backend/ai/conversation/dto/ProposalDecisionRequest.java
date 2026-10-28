package com.sw1.backend.ai.conversation.dto;

import jakarta.validation.constraints.Size;

public record ProposalDecisionRequest(
        @Size(max = 100)
        String clientId,
        @Size(max = 300)
        String comentario
) {
}
