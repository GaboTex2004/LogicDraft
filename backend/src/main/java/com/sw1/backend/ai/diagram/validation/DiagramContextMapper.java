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
                // Older persisted diagrams did not require edge IDs. Keep them valid for
                // ordinary contextual operations, while exposing the real ID when present
                // so association conversion can require it explicitly.
                String relationshipId = optionalText(edge.get("id"));
                String source = namesById.get(text(edge.get("source")));
                String target = namesById.get(text(edge.get("target")));
                if (source == null || target == null) throw new IllegalArgumentException();
                // React Flow edge.type is a visual style, never a cardinality.
                Map<?, ?> data = edge.get("data") == null ? Map.of() : map(edge.get("data"));
                if (data.containsKey("sourceCardinality") || data.containsKey("targetCardinality")) {
                    relationships.add(new RelationshipDefinition(source, target,
                        DiagramCardinality.valueOf(text(data.get("sourceCardinality"))),
                        DiagramCardinality.valueOf(text(data.get("targetCardinality"))),
                        optionalText(data.get("name")), optionalText(data.get("joinTableName")), relationshipId));
                } else {
                    Object kind = data.get("relationshipType");
                    RelationshipType type = kind == null ? null : RelationshipType.valueOf(text(kind));
                    RelationshipDefinition legacy = RelationshipDefinition.fromLegacy(source, target, type);
                    relationships.add(new RelationshipDefinition(legacy.sourceEntity(), legacy.targetEntity(),
                            legacy.sourceCardinality(), legacy.targetCardinality(), legacy.name(),
                            legacy.joinTableName(), relationshipId));
                }
            }
            List<DiagramAssociationContext> associations = new ArrayList<>();
            for (Object raw : list(document.get("nodes"))) {
                Map<?, ?> node = map(raw);
                Map<?, ?> data = map(node.get("data"));
                if (data.get("association") == null) continue;
                Map<?, ?> association = map(data.get("association"));
                if (!"MANY_TO_MANY_ASSOCIATION".equals(text(association.get("kind")))
                        || !bool(association.get("uniquePair"))) throw new IllegalArgumentException();
                List<String> endpointNames = new ArrayList<>();
                List<String> relationshipIds = new ArrayList<>();
                for (Object endpointRaw : list(association.get("endpoints"))) {
                    Map<?, ?> endpoint = map(endpointRaw);
                    String endpointName = namesById.get(text(endpoint.get("entityId")));
                    if (endpointName == null) throw new IllegalArgumentException();
                    endpointNames.add(endpointName);
                    relationshipIds.add(text(endpoint.get("relationshipId")));
                }
                if (endpointNames.size() != 2) throw new IllegalArgumentException();
                associations.add(new DiagramAssociationContext(text(data.get("name")),
                        text(association.get("tableName")), List.copyOf(endpointNames),
                        List.copyOf(relationshipIds)));
            }
            return new DiagramContext(List.copyOf(entities), List.copyOf(relationships), List.copyOf(associations));
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
    private static String optionalText(Object value) { return value == null ? null : text(value); }
}
