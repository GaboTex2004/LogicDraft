package com.sw1.backend.generator.mapper;

import com.sw1.backend.generator.naming.TechnicalNameNormalizer;
import com.sw1.backend.generator.schema.*;
import com.sw1.backend.generator.validation.ApplicationSchemaException;
import com.sw1.backend.generator.validation.ApplicationSchemaValidator;
import java.util.*;

public final class ApplicationSchemaMapper {
    public static final int SCHEMA_VERSION = 1;

    private ApplicationSchemaMapper() {
    }

    public static ApplicationSchema fromDocument(String projectName, Map<String, Object> document) {
        if (document == null || !Integer.valueOf(1).equals(document.get("version"))) {
            throw new ApplicationSchemaException("La version del diagrama no esta soportada.");
        }
        List<ApplicationEntity> entities = new ArrayList<>();
        for (Object rawNode : list(document.get("nodes"), "nodes")) {
            Map<?, ?> node = map(rawNode, "nodo");
            String entityId = text(node.get("id"), "ID de entidad");
            Map<?, ?> data = map(node.get("data"), "data de entidad " + entityId);
            String entityName = text(data.get("name"), "nombre de entidad");
            List<ApplicationField> fields = new ArrayList<>();
            for (Object rawAttribute : list(data.get("attributes"), "atributos de " + entityName)) {
                Map<?, ?> attribute = map(rawAttribute, "atributo de " + entityName);
                String fieldName = text(attribute.get("name"), "nombre de atributo de " + entityName);
                boolean primaryKey = bool(attribute.get("primaryKey"), "primaryKey de " + fieldName);
                boolean nullable = attribute.containsKey("nullable")
                        ? bool(attribute.get("nullable"), "nullable de " + fieldName) : !primaryKey;
                CanonicalType type = canonicalType(text(attribute.get("type"), "tipo de " + fieldName), entityName, fieldName);
                String fieldId = text(attribute.get("id"), "ID del atributo " + fieldName);
                boolean generated = primaryKey && (type == CanonicalType.INTEGER || type == CanonicalType.LONG);
                fields.add(new ApplicationField(fieldId, fieldName,
                        TechnicalNameNormalizer.fieldName(fieldName), type, primaryKey, nullable, generated));
            }
            ApplicationAssociation association = data.get("association") == null ? null
                    : association(data.get("association"), entityName);
            entities.add(new ApplicationEntity(entityId, entityName,
                    TechnicalNameNormalizer.typeName(entityName), List.copyOf(fields), association));
        }

        List<ApplicationRelationship> relationships = new ArrayList<>();
        for (Object rawEdge : list(document.get("edges"), "edges")) {
            Map<?, ?> edge = map(rawEdge, "relacion");
            String id = text(edge.get("id"), "ID de relacion");
            String source = text(edge.get("source"), "source de " + id);
            String target = text(edge.get("target"), "target de " + id);
            Map<?, ?> data = edge.get("data") == null ? Map.of() : map(edge.get("data"), "data de " + id);
            ApplicationCardinality[] cards = cardinalities(data, id);
            String relationshipName = optionalText(data.get("name"), "nombre de relacion " + id);
            String joinTableName = optionalText(data.get("joinTableName"), "tabla intermedia de " + id);
            relationships.add(new ApplicationRelationship(id, source, target, cards[0], cards[1],
                    relationshipName, joinTableName));
        }

        ApplicationSchema result = new ApplicationSchema(SCHEMA_VERSION, projectName, projectName,
                TechnicalNameNormalizer.typeName(projectName), List.copyOf(entities), List.copyOf(relationships));
        return ApplicationSchemaValidator.validate(result);
    }

    private static CanonicalType canonicalType(String value, String entity, String field) {
        return switch (value.toUpperCase(Locale.ROOT)) {
            case "VARCHAR", "TEXT", "STRING" -> CanonicalType.STRING;
            case "INTEGER", "INT" -> CanonicalType.INTEGER;
            case "BIGINT", "LONG" -> CanonicalType.LONG;
            case "DECIMAL", "DOUBLE", "FLOAT" -> CanonicalType.DECIMAL;
            case "BOOLEAN", "BOOL" -> CanonicalType.BOOLEAN;
            case "DATE" -> CanonicalType.DATE;
            case "TIMESTAMP", "DATETIME" -> CanonicalType.DATETIME;
            default -> throw new ApplicationSchemaException("El atributo " + field + " de " + entity
                    + " utiliza un tipo no soportado: " + value + ".");
        };
    }

    private static ApplicationAssociation association(Object value, String entityName) {
        Map<?, ?> association = map(value, "asociacion de " + entityName);
        AssociationKind kind;
        try {
            kind = AssociationKind.valueOf(text(association.get("kind"), "kind de asociacion de " + entityName));
        } catch (IllegalArgumentException exception) {
            throw new ApplicationSchemaException("La asociacion de " + entityName + " utiliza un kind no soportado.");
        }
        List<ApplicationAssociationEndpoint> endpoints = new ArrayList<>();
        for (Object rawEndpoint : list(association.get("endpoints"), "endpoints de " + entityName)) {
            Map<?, ?> endpoint = map(rawEndpoint, "endpoint de asociacion de " + entityName);
            AssociationEndpointRole role;
            try {
                role = AssociationEndpointRole.valueOf(text(endpoint.get("role"), "role de endpoint de " + entityName));
            } catch (IllegalArgumentException exception) {
                throw new ApplicationSchemaException("La asociacion de " + entityName + " utiliza un role no soportado.");
            }
            endpoints.add(new ApplicationAssociationEndpoint(role,
                    text(endpoint.get("entityId"), "entityId de endpoint de " + entityName),
                    text(endpoint.get("relationshipId"), "relationshipId de endpoint de " + entityName),
                    text(endpoint.get("foreignKeyName"), "foreignKeyName de endpoint de " + entityName)));
        }
        return new ApplicationAssociation(kind,
                text(association.get("tableName"), "tableName de asociacion de " + entityName),
                List.copyOf(endpoints),
                bool(association.get("uniquePair"), "uniquePair de asociacion de " + entityName));
    }

    private static ApplicationCardinality[] cardinalities(Map<?, ?> data, String id) {
        try {
            if (data.containsKey("sourceCardinality") || data.containsKey("targetCardinality")) {
                return new ApplicationCardinality[]{
                        ApplicationCardinality.valueOf(text(data.get("sourceCardinality"), "sourceCardinality de " + id)),
                        ApplicationCardinality.valueOf(text(data.get("targetCardinality"), "targetCardinality de " + id))};
            }
            Object legacy = data.get("relationshipType");
            if (legacy == null || "ONE_TO_ONE".equals(legacy)) {
                return new ApplicationCardinality[]{ApplicationCardinality.ONE_ONE, ApplicationCardinality.ONE_ONE};
            }
            if ("ONE_TO_MANY".equals(legacy)) {
                return new ApplicationCardinality[]{ApplicationCardinality.ONE_ONE, ApplicationCardinality.ZERO_MANY};
            }
            if ("MANY_TO_ONE".equals(legacy)) {
                return new ApplicationCardinality[]{ApplicationCardinality.ZERO_MANY, ApplicationCardinality.ONE_ONE};
            }
            if ("MANY_TO_MANY".equals(legacy)) {
                return new ApplicationCardinality[]{ApplicationCardinality.ZERO_MANY, ApplicationCardinality.ZERO_MANY};
            }
        } catch (IllegalArgumentException ignored) {
            // Converted below to a domain-specific, controlled validation error.
        }
        throw new ApplicationSchemaException("La relacion '" + id + "' utiliza cardinalidades no soportadas.");
    }

    private static Map<?, ?> map(Object value, String subject) {
        if (!(value instanceof Map<?, ?> result)) throw new ApplicationSchemaException("La estructura de " + subject + " no es valida.");
        return result;
    }

    private static List<?> list(Object value, String subject) {
        if (!(value instanceof List<?> result)) throw new ApplicationSchemaException("La estructura de " + subject + " no es valida.");
        return result;
    }

    private static String text(Object value, String subject) {
        if (!(value instanceof String result) || result.isBlank() || !result.equals(result.strip()) || result.length() > 200) {
            throw new ApplicationSchemaException(subject + " no es valido.");
        }
        return result;
    }

    private static String optionalText(Object value, String subject) {
        return value == null ? null : text(value, subject);
    }

    private static boolean bool(Object value, String subject) {
        if (!(value instanceof Boolean result)) throw new ApplicationSchemaException(subject + " no es valido.");
        return result;
    }
}
