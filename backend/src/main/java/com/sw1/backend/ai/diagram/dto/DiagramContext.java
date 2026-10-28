package com.sw1.backend.ai.diagram.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.List;
import java.util.Set;

/** Semantic projection of a UML v1 or UML v2 nodes/edges document, not a persistence format. */
public record DiagramContext(
        List<EntityDefinition> entities,
        List<RelationshipDefinition> relationships,
        List<DiagramAssociationContext> associations,
        @JsonIgnore Set<String> readOnlyElementNames) {

    public DiagramContext {
        readOnlyElementNames = readOnlyElementNames == null ? Set.of() : Set.copyOf(readOnlyElementNames);
    }

    public DiagramContext(
            List<EntityDefinition> entities,
            List<RelationshipDefinition> relationships,
            List<DiagramAssociationContext> associations) {
        this(entities, relationships, associations, Set.of());
    }

    public DiagramContext(List<EntityDefinition> entities, List<RelationshipDefinition> relationships) {
        this(entities, relationships, List.of(), Set.of());
    }

    @Override
    @JsonIgnore
    public Set<String> readOnlyElementNames() {
        return readOnlyElementNames;
    }
}
