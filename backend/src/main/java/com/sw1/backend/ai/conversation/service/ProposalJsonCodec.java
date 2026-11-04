package com.sw1.backend.ai.conversation.service;

import com.sw1.backend.ai.diagram.dto.AssociationConversionDefinition;
import com.sw1.backend.ai.diagram.dto.AssociationCreationDefinition;
import com.sw1.backend.ai.diagram.dto.AttributeDefinition;
import com.sw1.backend.ai.diagram.dto.DiagramOperation;
import com.sw1.backend.ai.diagram.dto.EntityDefinition;
import com.sw1.backend.ai.diagram.dto.MethodDefinition;
import com.sw1.backend.ai.diagram.dto.ParameterDefinition;
import com.sw1.backend.ai.diagram.dto.RelationshipDefinition;
import com.sw1.backend.ai.diagram.validation.DiagramOperationValidator;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure-Java deterministic JSON-map codec for persisting and restoring {@link DiagramOperation}
 * lists and affected element IDs in PostgreSQL {@code jsonb} columns.
 */
public final class ProposalJsonCodec {
    private ProposalJsonCodec() {
    }

    public static Map<String, Object> encodeOperations(List<DiagramOperation> operations) {
        List<Map<String, Object>> serialized = new ArrayList<>();
        if (operations != null) {
            for (DiagramOperation op : operations) {
                serialized.add(encodeOperation(op));
            }
        }
        return Map.of("operations", List.copyOf(serialized));
    }

    public static List<DiagramOperation> decodeOperations(Map<String, Object> raw) {
        if (raw == null || !(raw.get("operations") instanceof List<?>)) {
            return List.of();
        }
        return DiagramOperationValidator.validate(raw).operations();
    }

    public static Map<String, Object> encodeAffectedIds(List<String> ids) {
        List<String> clean = new ArrayList<>();
        if (ids != null) {
            for (String id : ids) {
                if (id != null && !id.isBlank()) {
                    clean.add(id);
                }
            }
        }
        return Map.of("ids", List.copyOf(clean));
    }

    public static List<String> decodeAffectedIds(Map<String, Object> raw) {
        if (raw == null || !(raw.get("ids") instanceof List<?> list)) {
            return List.of();
        }
        List<String> ids = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof String text && !text.isBlank()) {
                ids.add(text);
            }
        }
        return List.copyOf(ids);
    }

    private static Map<String, Object> encodeOperation(DiagramOperation op) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("type", op.type().name());
        switch (op.type()) {
            case ADD_ENTITY -> map.put("entity", encodeEntity(op.entity()));
            case DELETE_ENTITY -> map.put("entityName", op.entityName());
            case RENAME_ENTITY -> {
                map.put("entityName", op.entityName());
                map.put("newName", op.newName());
            }
            case UPDATE_CLASSIFIER -> {
                map.put("entityName", op.entityName());
                if (op.kind() != null) {
                    map.put("kind", op.kind());
                }
                if (op.abstractValue() != null) {
                    map.put("abstract", op.abstractValue());
                }
            }
            case ADD_ATTRIBUTE -> {
                map.put("entityName", op.entityName());
                map.put("attribute", encodeAttribute(op.attribute()));
            }
            case DELETE_ATTRIBUTE -> {
                map.put("entityName", op.entityName());
                map.put("attributeName", op.attributeName());
            }
            case RENAME_ATTRIBUTE -> {
                map.put("entityName", op.entityName());
                map.put("attributeName", op.attributeName());
                map.put("newName", op.newName());
            }
            case CHANGE_ATTRIBUTE_TYPE -> {
                map.put("entityName", op.entityName());
                map.put("attributeName", op.attributeName());
                map.put("dataType", op.dataType().name());
            }
            case SET_ATTRIBUTE_PRIMARY_KEY, SET_ATTRIBUTE_NULLABLE, SET_ATTRIBUTE_STATIC -> {
                map.put("entityName", op.entityName());
                map.put("attributeName", op.attributeName());
                map.put("value", op.value() != null ? op.value() : op.staticValue());
            }
            case SET_ATTRIBUTE_VISIBILITY -> {
                map.put("entityName", op.entityName());
                map.put("attributeName", op.attributeName());
                map.put("visibility", op.visibility());
            }
            case ADD_METHOD -> {
                map.put("entityName", op.entityName());
                map.put("method", encodeMethod(op.method()));
            }
            case UPDATE_METHOD -> {
                map.put("entityName", op.entityName());
                map.put("methodName", op.methodName());
                if (op.parameterTypes() != null) {
                    map.put("parameterTypes", op.parameterTypes());
                }
                if (op.newName() != null) {
                    map.put("newName", op.newName());
                }
                if (op.returnType() != null) {
                    map.put("returnType", op.returnType());
                }
                if (op.visibility() != null) {
                    map.put("visibility", op.visibility());
                }
                if (op.staticValue() != null) {
                    map.put("static", op.staticValue());
                }
                if (op.abstractValue() != null) {
                    map.put("abstract", op.abstractValue());
                }
                if (op.method() != null) {
                    map.put("method", encodeMethod(op.method()));
                }
            }
            case DELETE_METHOD -> {
                map.put("entityName", op.entityName());
                map.put("methodName", op.methodName());
                if (op.parameterTypes() != null) {
                    map.put("parameterTypes", op.parameterTypes());
                }
            }
            case ADD_METHOD_PARAMETER -> {
                map.put("entityName", op.entityName());
                map.put("methodName", op.methodName());
                if (op.parameterTypes() != null) {
                    map.put("parameterTypes", op.parameterTypes());
                }
                map.put("parameter", encodeParameter(op.parameter()));
            }
            case UPDATE_METHOD_PARAMETER -> {
                map.put("entityName", op.entityName());
                map.put("methodName", op.methodName());
                if (op.parameterTypes() != null) {
                    map.put("parameterTypes", op.parameterTypes());
                }
                map.put("parameterName", op.parameterName());
                if (op.newName() != null) {
                    map.put("newName", op.newName());
                }
                if (op.newType() != null) {
                    map.put("newType", op.newType());
                }
                if (op.parameter() != null) {
                    map.put("parameter", encodeParameter(op.parameter()));
                }
            }
            case DELETE_METHOD_PARAMETER -> {
                map.put("entityName", op.entityName());
                map.put("methodName", op.methodName());
                if (op.parameterTypes() != null) {
                    map.put("parameterTypes", op.parameterTypes());
                }
                map.put("parameterName", op.parameterName());
            }
            case ADD_ENUM_LITERAL, DELETE_ENUM_LITERAL -> {
                map.put("entityName", op.entityName());
                map.put("literal", op.literal());
            }
            case RENAME_ENUM_LITERAL -> {
                map.put("entityName", op.entityName());
                map.put("literal", op.literal());
                map.put("newLiteral", op.newLiteral() != null ? op.newLiteral() : op.newName());
            }
            case ADD_RELATIONSHIP, DELETE_RELATIONSHIP, UPDATE_RELATIONSHIP ->
                    map.put("relationship", encodeRelationship(op.relationship()));
            case CREATE_ASSOCIATION -> map.put("association", encodeAssociation(op.association()));
            case DELETE_ASSOCIATION -> map.put("associationEntityName", op.associationEntityName());
            case CONVERT_MANY_TO_MANY_ASSOCIATION -> map.put("conversion", encodeConversion(op.conversion()));
        }
        return map;
    }

    private static Map<String, Object> encodeEntity(EntityDefinition entity) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", entity.name());
        map.put("attributes", entity.attributes().stream().map(ProposalJsonCodec::encodeAttribute).toList());
        if (entity.kind() != null) {
            map.put("kind", entity.kind());
        }
        if (entity.isAbstract() != null) {
            map.put("abstract", entity.isAbstract());
        }
        if (entity.methods() != null) {
            map.put("methods", entity.methods().stream().map(ProposalJsonCodec::encodeMethod).toList());
        }
        if (entity.literals() != null) {
            map.put("literals", entity.literals());
        }
        return map;
    }

    private static Map<String, Object> encodeAttribute(AttributeDefinition attr) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", attr.name());
        map.put("dataType", attr.dataType().name());
        map.put("primaryKey", attr.primaryKey());
        map.put("nullable", attr.nullable());
        if (attr.visibility() != null) {
            map.put("visibility", attr.visibility());
        }
        if (attr.isStatic() != null) {
            map.put("static", attr.isStatic());
        }
        return map;
    }

    private static Map<String, Object> encodeMethod(MethodDefinition method) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", method.name());
        if (method.returnType() != null) {
            map.put("returnType", method.returnType());
        }
        if (method.visibility() != null) {
            map.put("visibility", method.visibility());
        }
        if (method.isStatic() != null) {
            map.put("static", method.isStatic());
        }
        if (method.isAbstract() != null) {
            map.put("abstract", method.isAbstract());
        }
        if (method.parameters() != null) {
            map.put("parameters", method.parameters().stream().map(ProposalJsonCodec::encodeParameter).toList());
        }
        return map;
    }

    private static Map<String, Object> encodeParameter(ParameterDefinition parameter) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", parameter.name());
        if (parameter.type() != null) {
            map.put("type", parameter.type());
        }
        return map;
    }

    private static Map<String, Object> encodeRelationship(RelationshipDefinition rel) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("sourceEntity", rel.sourceEntity());
        map.put("targetEntity", rel.targetEntity());
        if (rel.sourceCardinality() != null) {
            map.put("sourceCardinality", rel.sourceCardinality().name());
        }
        if (rel.targetCardinality() != null) {
            map.put("targetCardinality", rel.targetCardinality().name());
        }
        if (rel.name() != null) {
            map.put("name", rel.name());
        }
        if (rel.joinTableName() != null) {
            map.put("joinTableName", rel.joinTableName());
        }
        if (rel.id() != null) {
            map.put("id", rel.id());
        }
        if (rel.kind() != null) {
            map.put("kind", rel.kind());
        }
        if (rel.sourceMultiplicity() != null) {
            map.put("sourceMultiplicity", rel.sourceMultiplicity());
        }
        if (rel.targetMultiplicity() != null) {
            map.put("targetMultiplicity", rel.targetMultiplicity());
        }
        if (rel.aggregationEnd() != null) {
            map.put("aggregationEnd", rel.aggregationEnd());
        }
        return map;
    }

    private static Map<String, Object> encodeAssociation(AssociationCreationDefinition assoc) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("sourceEntity", assoc.sourceEntity());
        map.put("targetEntity", assoc.targetEntity());
        map.put("associationEntityName", assoc.associationEntityName());
        map.put("attributes", assoc.attributes().stream().map(ProposalJsonCodec::encodeAttribute).toList());
        return map;
    }

    private static Map<String, Object> encodeConversion(AssociationConversionDefinition conv) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("relationshipId", conv.relationshipId());
        map.put("sourceEntity", conv.sourceEntity());
        map.put("targetEntity", conv.targetEntity());
        map.put("associationEntityName", conv.associationEntityName());
        map.put("attributes", conv.attributes().stream().map(ProposalJsonCodec::encodeAttribute).toList());
        return map;
    }
}
