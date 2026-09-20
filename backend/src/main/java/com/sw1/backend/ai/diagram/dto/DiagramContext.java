package com.sw1.backend.ai.diagram.dto;

import java.util.List;

/** Semantic projection of the existing version-1 nodes/edges document, not a persistence format. */
public record DiagramContext(List<EntityDefinition> entities, List<RelationshipDefinition> relationships,
                             List<DiagramAssociationContext> associations) {
    public DiagramContext(List<EntityDefinition> entities, List<RelationshipDefinition> relationships) {
        this(entities, relationships, List.of());
    }
}
