package com.sw1.backend.ai.diagram.validation;

import com.sw1.backend.ai.client.AiServiceException;
import com.sw1.backend.ai.diagram.dto.*;
import com.sw1.backend.ai.diagram.model.*;
import java.util.*;
import org.springframework.http.HttpStatus;

public final class DiagramContextMapper {
    private DiagramContextMapper() {}

    public static DiagramContext fromDocument(Map<String, Object> document) {
        try {
            if (document == null || !Integer.valueOf(1).equals(document.get("version")))
                throw new IllegalArgumentException();
            Map<String, String> namesById = new HashMap<>();
            Set<String> entityNames = new HashSet<>();
            List<EntityDefinition> entities = new ArrayList<>();
            for (Object raw : list(document.get("nodes"))) {
                Map<?, ?> node = map(raw);
                String id = text(node.get("id"));
                Map<?, ?> data = map(node.get("data"));
                String name = text(data.get("name"));
                if (namesById.putIfAbsent(id, name) != null || !entityNames.add(key(name)))
                    throw new IllegalArgumentException();
                List<AttributeDefinition> attributes = new ArrayList<>();
                Set<String> attributeNames = new HashSet<>();
                for (Object value : list(data.get("attributes"))) {
                    Map<?, ?> a = map(value);
                    String attributeName = text(a.get("name"));
                    if (!attributeNames.add(key(attributeName))) throw new IllegalArgumentException();
                    boolean pk = bool(a.get("primaryKey"));
                    boolean nullable = a.containsKey("nullable") ? bool(a.get("nullable")) : !pk;
                    attributes.add(new AttributeDefinition(attributeName, dataType(text(a.get("type"))), pk, nullable));
                }
                entities.add(new EntityDefinition(name, List.copyOf(attributes)));
            }
            List<RelationshipDefinition> relationships = new ArrayList<>();
            for (Object raw : list(document.get("edges"))) {
                Map<?, ?> edge = map(raw);
                String source = namesById.get(text(edge.get("source")));
                String target = namesById.get(text(edge.get("target")));
                if (source == null || target == null) throw new IllegalArgumentException();
                // React Flow edge.type is a visual style, never a cardinality.
                Map<?, ?> data = edge.get("data") == null ? Map.of() : map(edge.get("data"));
                if (data.containsKey("sourceCardinality") || data.containsKey("targetCardinality")) {
                    relationships.add(new RelationshipDefinition(source, target,
                        DiagramCardinality.valueOf(text(data.get("sourceCardinality"))),
                        DiagramCardinality.valueOf(text(data.get("targetCardinality")))));
                } else {
                    Object kind = data.get("relationshipType");
                    RelationshipType type = kind == null ? null : RelationshipType.valueOf(text(kind));
                    relationships.add(RelationshipDefinition.fromLegacy(source, target, type));
                }
            }
            return new DiagramContext(List.copyOf(entities), List.copyOf(relationships));
        } catch (IllegalArgumentException e) {
            throw new AiServiceException(HttpStatus.CONFLICT, "El diagrama guardado no es valido para interpretacion contextual");
        }
    }

    public static String key(String value) { return value.toLowerCase(Locale.ROOT); }

    private static DiagramDataType dataType(String value) {
        return switch (value.toUpperCase(Locale.ROOT)) {
            case "VARCHAR", "TEXT", "STRING" -> DiagramDataType.String;
            case "BIGINT", "LONG" -> DiagramDataType.Long;
            case "INTEGER", "INT" -> DiagramDataType.Integer;
            case "DECIMAL", "DOUBLE", "FLOAT" -> DiagramDataType.Double;
            case "BOOLEAN", "BOOL" -> DiagramDataType.Boolean;
            case "DATE" -> DiagramDataType.Date;
            case "TIMESTAMP", "DATETIME" -> DiagramDataType.DateTime;
            default -> throw new IllegalArgumentException();
        };
    }

    private static Map<?, ?> map(Object value) {
        if (!(value instanceof Map<?, ?> m)) throw new IllegalArgumentException();
        return m;
    }
    private static List<?> list(Object value) {
        if (!(value instanceof List<?> l)) throw new IllegalArgumentException();
        return l;
    }
    private static String text(Object value) {
        if (!(value instanceof String s) || s.isBlank() || !s.equals(s.strip()) || s.length() > 100)
            throw new IllegalArgumentException();
        return s;
    }
    private static boolean bool(Object value) {
        if (!(value instanceof Boolean b)) throw new IllegalArgumentException();
        return b;
    }
}
