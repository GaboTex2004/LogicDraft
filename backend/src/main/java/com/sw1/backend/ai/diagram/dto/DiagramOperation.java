package com.sw1.backend.ai.diagram.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.sw1.backend.ai.diagram.model.DiagramDataType;
import com.sw1.backend.ai.diagram.model.DiagramOperationType;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record DiagramOperation(
        DiagramOperationType type,
        EntityDefinition entity,
        String entityName,
        AttributeDefinition attribute,
        RelationshipDefinition relationship,
        AssociationConversionDefinition conversion,
        String attributeName,
        String newName,
        DiagramDataType dataType,
        Boolean value,
        AssociationCreationDefinition association,
        String associationEntityName,
        String kind,
        @JsonProperty("abstract") Boolean abstractValue,
        String visibility,
        @JsonProperty("static") Boolean staticValue,
        MethodDefinition method,
        String methodName,
        List<String> parameterTypes,
        String returnType,
        ParameterDefinition parameter,
        String parameterName,
        String newType,
        String literal,
        String newLiteral) {

    public DiagramOperation(
            DiagramOperationType type,
            EntityDefinition entity,
            String entityName,
            AttributeDefinition attribute,
            RelationshipDefinition relationship) {
        this(type, entity, entityName, attribute, relationship, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    public DiagramOperation(
            DiagramOperationType type,
            EntityDefinition entity,
            String entityName,
            AttributeDefinition attribute,
            RelationshipDefinition relationship,
            AssociationConversionDefinition conversion) {
        this(type, entity, entityName, attribute, relationship, conversion, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    public DiagramOperation(
            DiagramOperationType type,
            EntityDefinition entity,
            String entityName,
            AttributeDefinition attribute,
            RelationshipDefinition relationship,
            AssociationConversionDefinition conversion,
            String attributeName,
            String newName,
            DiagramDataType dataType,
            Boolean value,
            AssociationCreationDefinition association,
            String associationEntityName) {
        this(type, entity, entityName, attribute, relationship, conversion, attributeName, newName, dataType, value,
                association, associationEntityName, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    public static DiagramOperation updateClassifier(String entityName, String kind, Boolean abstractValue) {
        return new DiagramOperation(DiagramOperationType.UPDATE_CLASSIFIER, null, entityName, null, null,
                null, null, null, null, null, null, null, kind, abstractValue, null, null, null, null, null, null, null, null, null, null, null);
    }

    public static DiagramOperation setAttributeVisibility(String entityName, String attributeName, String visibility) {
        return new DiagramOperation(DiagramOperationType.SET_ATTRIBUTE_VISIBILITY, null, entityName, null, null,
                null, attributeName, null, null, null, null, null, null, null, visibility, null, null, null, null, null, null, null, null, null, null);
    }

    public static DiagramOperation setAttributeStatic(String entityName, String attributeName, boolean isStatic) {
        return new DiagramOperation(DiagramOperationType.SET_ATTRIBUTE_STATIC, null, entityName, null, null,
                null, attributeName, null, null, isStatic, null, null, null, null, null, isStatic, null, null, null, null, null, null, null, null, null);
    }

    public static DiagramOperation addMethod(String entityName, MethodDefinition method) {
        return new DiagramOperation(DiagramOperationType.ADD_METHOD, null, entityName, null, null,
                null, null, null, null, null, null, null, null, null, null, null, method, null, null, null, null, null, null, null, null);
    }

    public static DiagramOperation updateMethod(
            String entityName,
            String methodName,
            List<String> parameterTypes,
            String newName,
            String returnType,
            String visibility,
            Boolean isStatic,
            Boolean isAbstract,
            MethodDefinition method) {
        return new DiagramOperation(DiagramOperationType.UPDATE_METHOD, null, entityName, null, null,
                null, null, newName, null, null, null, null, null, isAbstract, visibility, isStatic, method, methodName, parameterTypes, returnType, null, null, null, null, null);
    }

    public static DiagramOperation deleteMethod(String entityName, String methodName, List<String> parameterTypes) {
        return new DiagramOperation(DiagramOperationType.DELETE_METHOD, null, entityName, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, methodName, parameterTypes, null, null, null, null, null, null);
    }

    public static DiagramOperation addMethodParameter(
            String entityName,
            String methodName,
            List<String> parameterTypes,
            ParameterDefinition parameter) {
        return new DiagramOperation(DiagramOperationType.ADD_METHOD_PARAMETER, null, entityName, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, methodName, parameterTypes, null, parameter, null, null, null, null);
    }

    public static DiagramOperation updateMethodParameter(
            String entityName,
            String methodName,
            List<String> parameterTypes,
            String parameterName,
            String newName,
            String newType,
            ParameterDefinition parameter) {
        return new DiagramOperation(DiagramOperationType.UPDATE_METHOD_PARAMETER, null, entityName, null, null,
                null, null, newName, null, null, null, null, null, null, null, null, null, methodName, parameterTypes, null, parameter, parameterName, newType, null, null);
    }

    public static DiagramOperation deleteMethodParameter(
            String entityName,
            String methodName,
            List<String> parameterTypes,
            String parameterName) {
        return new DiagramOperation(DiagramOperationType.DELETE_METHOD_PARAMETER, null, entityName, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, methodName, parameterTypes, null, null, parameterName, null, null, null);
    }

    public static DiagramOperation addEnumLiteral(String entityName, String literal) {
        return new DiagramOperation(DiagramOperationType.ADD_ENUM_LITERAL, null, entityName, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, literal, null);
    }

    public static DiagramOperation renameEnumLiteral(String entityName, String literal, String newLiteral) {
        return new DiagramOperation(DiagramOperationType.RENAME_ENUM_LITERAL, null, entityName, null, null,
                null, null, newLiteral, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, literal, newLiteral);
    }

    public static DiagramOperation deleteEnumLiteral(String entityName, String literal) {
        return new DiagramOperation(DiagramOperationType.DELETE_ENUM_LITERAL, null, entityName, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, literal, null);
    }
}
