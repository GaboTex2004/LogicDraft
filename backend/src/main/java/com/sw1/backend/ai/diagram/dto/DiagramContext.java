package com.sw1.backend.ai.diagram.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.sw1.backend.ai.diagram.validation.DiagramContextMapper.ReadOnlyUmlElement;
import com.sw1.backend.ai.diagram.validation.DiagramContextMapper.ReadOnlyUmlRelation;
import com.sw1.backend.ai.diagram.validation.DiagramContextMapper.SemanticNode;
import com.sw1.backend.ai.diagram.validation.DiagramContextMapper.SemanticRelationship;
import java.util.List;
import java.util.Set;

/** Semantic projection of a UML v1 or UML v2 nodes/edges document, not a persistence format. */
public record DiagramContext(
        List<EntityDefinition> entities,
        List<RelationshipDefinition> relationships,
        List<DiagramAssociationContext> associations,
        @JsonIgnore Set<String> readOnlyElementNames,
        @JsonIgnore List<SemanticNode> semanticNodes,
        @JsonIgnore List<SemanticRelationship> semanticRelationships,
        @JsonIgnore List<ReadOnlyUmlElement> readOnlyElements,
        @JsonIgnore List<ReadOnlyUmlRelation> readOnlyRelations) {

    public DiagramContext {
        readOnlyElementNames = readOnlyElementNames == null ? Set.of() : Set.copyOf(readOnlyElementNames);
        semanticNodes = semanticNodes == null ? List.of() : List.copyOf(semanticNodes);
        semanticRelationships = semanticRelationships == null ? List.of() : List.copyOf(semanticRelationships);
        readOnlyElements = readOnlyElements == null ? List.of() : List.copyOf(readOnlyElements);
        readOnlyRelations = readOnlyRelations == null ? List.of() : List.copyOf(readOnlyRelations);
    }

    public DiagramContext(
            List<EntityDefinition> entities,
            List<RelationshipDefinition> relationships,
            List<DiagramAssociationContext> associations,
            Set<String> readOnlyElementNames) {
        this(entities, relationships, associations, readOnlyElementNames, List.of(), List.of(), List.of(), List.of());
    }

    public DiagramContext(
            List<EntityDefinition> entities,
            List<RelationshipDefinition> relationships,
            List<DiagramAssociationContext> associations) {
        this(entities, relationships, associations, Set.of(), List.of(), List.of(), List.of(), List.of());
    }

    public DiagramContext(List<EntityDefinition> entities, List<RelationshipDefinition> relationships) {
        this(entities, relationships, List.of(), Set.of(), List.of(), List.of(), List.of(), List.of());
    }

    @Override
    @JsonIgnore
    public Set<String> readOnlyElementNames() {
        return readOnlyElementNames;
    }

    @Override
    @JsonIgnore
    public List<SemanticNode> semanticNodes() {
        return semanticNodes;
    }

    @Override
    @JsonIgnore
    public List<SemanticRelationship> semanticRelationships() {
        return semanticRelationships;
    }

    @Override
    @JsonIgnore
    public List<ReadOnlyUmlElement> readOnlyElements() {
        return readOnlyElements;
    }

    @Override
    @JsonIgnore
    public List<ReadOnlyUmlRelation> readOnlyRelations() {
        return readOnlyRelations;
    }
}
