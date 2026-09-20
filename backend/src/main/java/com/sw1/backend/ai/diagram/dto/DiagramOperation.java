package com.sw1.backend.ai.diagram.dto;

import com.sw1.backend.ai.diagram.model.DiagramOperationType;
import com.fasterxml.jackson.annotation.JsonInclude;
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DiagramOperation(DiagramOperationType type, EntityDefinition entity, String entityName,
                               AttributeDefinition attribute, RelationshipDefinition relationship,
                               AssociationConversionDefinition conversion) {
    public DiagramOperation(DiagramOperationType type, EntityDefinition entity, String entityName,
                            AttributeDefinition attribute, RelationshipDefinition relationship) {
        this(type, entity, entityName, attribute, relationship, null);
    }
}
