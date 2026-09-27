package com.sw1.backend.ai.diagram.dto;

import com.sw1.backend.ai.diagram.model.DiagramOperationType;
import com.sw1.backend.ai.diagram.model.DiagramDataType;
import com.fasterxml.jackson.annotation.JsonInclude;
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DiagramOperation(DiagramOperationType type, EntityDefinition entity, String entityName,
                               AttributeDefinition attribute, RelationshipDefinition relationship,
                               AssociationConversionDefinition conversion, String attributeName,
                               String newName, DiagramDataType dataType, Boolean value,
                               AssociationCreationDefinition association, String associationEntityName) {
    public DiagramOperation(DiagramOperationType type, EntityDefinition entity, String entityName,
                            AttributeDefinition attribute, RelationshipDefinition relationship) {
        this(type, entity, entityName, attribute, relationship, null, null, null, null, null,
                null, null);
    }
    public DiagramOperation(DiagramOperationType type, EntityDefinition entity, String entityName,
                            AttributeDefinition attribute, RelationshipDefinition relationship,
                            AssociationConversionDefinition conversion) {
        this(type, entity, entityName, attribute, relationship, conversion, null, null, null, null,
                null, null);
    }
}
