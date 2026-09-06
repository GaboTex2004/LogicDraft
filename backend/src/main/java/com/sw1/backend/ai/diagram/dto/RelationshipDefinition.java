package com.sw1.backend.ai.diagram.dto;

import com.sw1.backend.ai.diagram.model.RelationshipType;
import com.sw1.backend.ai.diagram.model.DiagramCardinality;
import static com.sw1.backend.ai.diagram.model.DiagramCardinality.*;

public record RelationshipDefinition(String sourceEntity, String targetEntity,
        DiagramCardinality sourceCardinality, DiagramCardinality targetCardinality) {
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
