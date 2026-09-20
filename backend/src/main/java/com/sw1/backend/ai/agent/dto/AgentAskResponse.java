package com.sw1.backend.ai.agent.dto;

import com.sw1.backend.ai.diagram.dto.DiagramOperation;
import java.util.List;

public record AgentAskResponse(String answer, List<DiagramOperation> operations) {
    public AgentAskResponse(String answer) { this(answer, List.of()); }
}
