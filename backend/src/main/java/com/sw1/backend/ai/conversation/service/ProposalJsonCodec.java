package com.sw1.backend.ai.conversation.service;

import com.sw1.backend.ai.diagram.dto.AssociationConversionDefinition;
import com.sw1.backend.ai.diagram.dto.AssociationCreationDefinition;
import com.sw1.backend.ai.diagram.dto.AttributeDefinition;
import com.sw1.backend.ai.diagram.dto.DiagramOperation;
import com.sw1.backend.ai.diagram.dto.EntityDefinition;
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
            case SET_ATTRIBUTE_PRIMARY_KEY, SET_ATTRIBUTE_NULLABLE -> {
                map.put("entityName", op.entityName());
                map.put("attributeName", op.attributeName());
                map.put("value", op.value());
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
        return map;
    }

    private static Map<String, Object> encodeAttribute(AttributeDefinition attr) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", attr.name());
        map.put("dataType", attr.dataType().name());
        map.put("primaryKey", attr.primaryKey());
        map.put("nullable", attr.nullable());
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
