package com.sw1.backend.ai.diagram.validation;

import com.sw1.backend.ai.client.AiServiceException;
import com.sw1.backend.ai.diagram.dto.*;
import com.sw1.backend.ai.diagram.model.*;
import java.util.*;
import org.springframework.http.HttpStatus;

public final class DiagramOperationValidator {
    private DiagramOperationValidator() {}

    public static DiagramInterpretResponse validate(Map<String, Object> raw) {
        try {
            Map<?, ?> root = object(raw, "operations");
            List<DiagramOperation> operations = new ArrayList<>();
            for (Object item : list(root.get("operations"), 50)) {
                if (!(item instanceof Map<?, ?> op)) throw new IllegalArgumentException();
                DiagramOperationType type = DiagramOperationType.valueOf(text(op.get("type")));
                switch (type) {
                    case ADD_ENTITY -> {
                        object(op, "type", "entity");
                        Map<?, ?> entity = object(op.get("entity"), "name", "attributes");
                        List<AttributeDefinition> attributes = new ArrayList<>();
                        for (Object value : list(entity.get("attributes"), 100)) attributes.add(attribute(value));
                        operations.add(new DiagramOperation(type,
                            new EntityDefinition(text(entity.get("name")), List.copyOf(attributes)), null, null, null));
                    }
                    case ADD_ATTRIBUTE -> {
                        object(op, "type", "entityName", "attribute");
                        operations.add(new DiagramOperation(type, null, text(op.get("entityName")), attribute(op.get("attribute")), null));
                    }
                    case ADD_RELATIONSHIP -> {
                        object(op, "type", "relationship");
                        Map<?, ?> rel = objectWithOptional(op.get("relationship"),
                                Set.of("sourceEntity", "targetEntity", "sourceCardinality", "targetCardinality"),
                                Set.of("name", "joinTableName"));
                        operations.add(new DiagramOperation(type, null, null, null, new RelationshipDefinition(
                            text(rel.get("sourceEntity")), text(rel.get("targetEntity")),
                            DiagramCardinality.valueOf(text(rel.get("sourceCardinality"))),
                            DiagramCardinality.valueOf(text(rel.get("targetCardinality"))),
                            optionalText(rel.get("name")), optionalText(rel.get("joinTableName")))));
                    }
                    case CONVERT_MANY_TO_MANY_ASSOCIATION -> {
                        object(op, "type", "conversion");
                        Map<?, ?> conversion = object(op.get("conversion"), "relationshipId", "sourceEntity",
                                "targetEntity", "associationEntityName", "attributes");
                        List<AttributeDefinition> attributes = new ArrayList<>();
                        for (Object value : list(conversion.get("attributes"), 100)) {
                            AttributeDefinition attribute = attribute(value);
                            if (attribute.primaryKey()) throw new IllegalArgumentException();
                            attributes.add(attribute);
                        }
                        operations.add(new DiagramOperation(type, null, null, null, null,
                                new AssociationConversionDefinition(text(conversion.get("relationshipId")),
                                        text(conversion.get("sourceEntity")), text(conversion.get("targetEntity")),
                                        text(conversion.get("associationEntityName")), List.copyOf(attributes))));
                    }
                }
            }
            return new DiagramInterpretResponse(List.copyOf(operations));
        } catch (IllegalArgumentException exception) {
            throw new AiServiceException(HttpStatus.BAD_GATEWAY, "El servicio de IA devolvio operaciones invalidas");
        }
    }

    private static AttributeDefinition attribute(Object raw) {
        Map<?, ?> a = object(raw, "name", "dataType", "primaryKey", "nullable");
        if (!(a.get("primaryKey") instanceof Boolean pk) || !(a.get("nullable") instanceof Boolean nullable))
            throw new IllegalArgumentException();
        return new AttributeDefinition(text(a.get("name")), DiagramDataType.valueOf(text(a.get("dataType"))), pk, nullable);
    }

    private static Map<?, ?> object(Object raw, String... keys) {
        if (!(raw instanceof Map<?, ?> map) || !map.keySet().equals(Set.of(keys))) throw new IllegalArgumentException();
        return map;
    }
    private static Map<?, ?> objectWithOptional(Object raw, Set<String> required, Set<String> optional) {
        if (!(raw instanceof Map<?, ?> map) || !map.keySet().containsAll(required)
                || map.keySet().stream().anyMatch(key -> !(key instanceof String text)
                || !required.contains(text) && !optional.contains(text))) throw new IllegalArgumentException();
        return map;
    }

    private static List<?> list(Object raw, int max) {
        if (!(raw instanceof List<?> list) || list.size() > max) throw new IllegalArgumentException();
        return list;
    }

    private static String text(Object raw) {
        if (!(raw instanceof String s) || s.isBlank() || !s.equals(s.strip()) || s.length() > 100)
            throw new IllegalArgumentException();
        return s;
    }
    private static String optionalText(Object raw) { return raw == null ? null : text(raw); }
}
