package com.sw1.backend.ai.agent.dto;

import com.sw1.backend.ai.diagram.dto.AttributeDefinition;
import com.sw1.backend.ai.diagram.model.DiagramCardinality;
import java.util.List;

public record AgentProjectContext(
        Long projectId,
        String projectName,
        Long diagramId,
        String selectedNodeId,
        String selectedEdgeId,
        List<AgentEntity> entities,
        List<AgentRelationship> relationships,
        List<AgentEventInput> recentEvents) {

    public record AgentEntity(String id, String name, List<AttributeDefinition> attributes) {}

    public record AgentRelationship(
            String id,
            String sourceNodeId,
            String targetNodeId,
            String sourceEntity,
            String targetEntity,
            DiagramCardinality sourceCardinality,
            DiagramCardinality targetCardinality) {}
}
