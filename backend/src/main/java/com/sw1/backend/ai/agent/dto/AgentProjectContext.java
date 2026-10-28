package com.sw1.backend.ai.agent.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.sw1.backend.ai.diagram.dto.AttributeDefinition;
import com.sw1.backend.ai.diagram.model.DiagramCardinality;
import java.util.List;
import java.util.Set;

public record AgentProjectContext(
        Long projectId,
        String projectName,
        Long diagramId,
        String selectedNodeId,
        String selectedEdgeId,
        List<AgentEntity> entities,
        List<AgentRelationship> relationships,
        List<AgentAssociation> associations,
        List<AgentEventInput> recentEvents,
        String projectDescription,
        @JsonIgnore Set<String> readOnlyElementNames) {

    public AgentProjectContext {
        readOnlyElementNames = readOnlyElementNames == null ? Set.of() : Set.copyOf(readOnlyElementNames);
    }

    public AgentProjectContext(Long projectId, String projectName, Long diagramId, String selectedNodeId,
                               String selectedEdgeId, List<AgentEntity> entities,
                               List<AgentRelationship> relationships, List<AgentAssociation> associations,
                               List<AgentEventInput> recentEvents, String projectDescription) {
        this(projectId, projectName, diagramId, selectedNodeId, selectedEdgeId,
                entities, relationships, associations, recentEvents, projectDescription, Set.of());
    }

    public AgentProjectContext(Long projectId, String projectName, Long diagramId, String selectedNodeId,
                               String selectedEdgeId, List<AgentEntity> entities,
                               List<AgentRelationship> relationships, List<AgentAssociation> associations,
                               List<AgentEventInput> recentEvents) {
        this(projectId, projectName, diagramId, selectedNodeId, selectedEdgeId,
                entities, relationships, associations, recentEvents, null, Set.of());
    }

    public AgentProjectContext(Long projectId, String projectName, Long diagramId, String selectedNodeId,
                               String selectedEdgeId, List<AgentEntity> entities,
                               List<AgentRelationship> relationships, List<AgentEventInput> recentEvents) {
        this(projectId, projectName, diagramId, selectedNodeId, selectedEdgeId,
                entities, relationships, List.of(), recentEvents, null, Set.of());
    }

    @Override
    @JsonIgnore
    public Set<String> readOnlyElementNames() {
        return readOnlyElementNames;
    }

    public record AgentEntity(String id, String name, List<AttributeDefinition> attributes) {}

    public record AgentRelationship(
            String id,
            String sourceNodeId,
            String targetNodeId,
            String sourceEntity,
            String targetEntity,
            DiagramCardinality sourceCardinality,
            DiagramCardinality targetCardinality,
            String name,
            String joinTableName) {
        public AgentRelationship(String id, String sourceNodeId, String targetNodeId, String sourceEntity,
                                 String targetEntity, DiagramCardinality sourceCardinality,
                                 DiagramCardinality targetCardinality) {
            this(id, sourceNodeId, targetNodeId, sourceEntity, targetEntity,
                    sourceCardinality, targetCardinality, null, null);
        }
    }

    public record AgentAssociation(String entityName, String tableName,
                                   List<String> endpointEntityNames,
                                   List<String> structuralRelationshipIds) {}
}
