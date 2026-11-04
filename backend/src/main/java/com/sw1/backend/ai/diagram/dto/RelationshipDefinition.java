package com.sw1.backend.ai.diagram.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.sw1.backend.ai.diagram.model.DiagramCardinality;
import com.sw1.backend.ai.diagram.model.RelationshipType;
import static com.sw1.backend.ai.diagram.model.DiagramCardinality.*;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record RelationshipDefinition(
        String sourceEntity,
        String targetEntity,
        DiagramCardinality sourceCardinality,
        DiagramCardinality targetCardinality,
        String name,
        String joinTableName,
        String id,
        String kind,
        String sourceMultiplicity,
        String targetMultiplicity,
        String aggregationEnd) {

    public RelationshipDefinition(
            String sourceEntity,
            String targetEntity,
            DiagramCardinality sourceCardinality,
            DiagramCardinality targetCardinality,
            String name,
            String joinTableName,
            String id) {
        this(sourceEntity, targetEntity, sourceCardinality, targetCardinality, name, joinTableName, id, null, null, null, null);
    }

    public RelationshipDefinition(
            String sourceEntity,
            String targetEntity,
            DiagramCardinality sourceCardinality,
            DiagramCardinality targetCardinality,
            String name,
            String joinTableName) {
        this(sourceEntity, targetEntity, sourceCardinality, targetCardinality, name, joinTableName, null, null, null, null, null);
    }

    public RelationshipDefinition(
            String sourceEntity,
            String targetEntity,
            DiagramCardinality sourceCardinality,
            DiagramCardinality targetCardinality) {
        this(sourceEntity, targetEntity, sourceCardinality, targetCardinality, null, null, null, null, null, null, null);
    }

    // Compatibility for saved JSON only; legacy 'many' never specified a minimum.
    public static RelationshipDefinition fromLegacy(String source, String target, RelationshipType type) {
        if (type == null) return new RelationshipDefinition(source, target, ONE_ONE, ONE_ONE);
        return switch (type) {
            case ONE_TO_ONE -> new RelationshipDefinition(source, target, ONE_ONE, ONE_ONE);
            case ONE_TO_MANY -> new RelationshipDefinition(source, target, ONE_ONE, ZERO_MANY);
            case MANY_TO_ONE -> new RelationshipDefinition(source, target, ZERO_MANY, ONE_ONE);
            case MANY_TO_MANY -> new RelationshipDefinition(source, target, ZERO_MANY, ZERO_MANY);
        };
    }
}
