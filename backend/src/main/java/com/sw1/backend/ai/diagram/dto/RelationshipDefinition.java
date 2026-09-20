package com.sw1.backend.ai.diagram.dto;

import com.sw1.backend.ai.diagram.model.RelationshipType;
import com.sw1.backend.ai.diagram.model.DiagramCardinality;
import static com.sw1.backend.ai.diagram.model.DiagramCardinality.*;
import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record RelationshipDefinition(String sourceEntity, String targetEntity,
        DiagramCardinality sourceCardinality, DiagramCardinality targetCardinality,
        String name, String joinTableName, String id) {
    public RelationshipDefinition(String sourceEntity, String targetEntity,
                                  DiagramCardinality sourceCardinality, DiagramCardinality targetCardinality,
                                  String name, String joinTableName) {
        this(sourceEntity, targetEntity, sourceCardinality, targetCardinality, name, joinTableName, null);
    }
    public RelationshipDefinition(String sourceEntity, String targetEntity,
                                  DiagramCardinality sourceCardinality, DiagramCardinality targetCardinality) {
        this(sourceEntity, targetEntity, sourceCardinality, targetCardinality, null, null);
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
