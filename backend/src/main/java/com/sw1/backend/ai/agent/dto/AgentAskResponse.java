package com.sw1.backend.ai.agent.dto;

import com.sw1.backend.ai.diagram.dto.DiagramOperation;
import java.util.List;

public record AgentAskResponse(String answer, List<DiagramOperation> operations,
        Intent intent, Outcome outcome, int repairAttempts) {
    public enum Intent { INFORMATIONAL, MUTATION, NEEDS_CLARIFICATION }
    public enum Outcome { INFORMATIONAL, PROPOSAL, ALREADY_SATISFIED, NEEDS_CLARIFICATION }
    public AgentAskResponse(String answer, List<DiagramOperation> operations) {
        this(answer, operations, null, null, 0);
    }
    public AgentAskResponse(String answer) { this(answer, List.of()); }
}
