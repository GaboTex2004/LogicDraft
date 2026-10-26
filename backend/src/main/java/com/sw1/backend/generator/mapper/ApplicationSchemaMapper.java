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
        if (document == null || !(Integer.valueOf(1).equals(document.get("version"))
                || Integer.valueOf(2).equals(document.get("version")))) {
            throw new ApplicationSchemaException("La version del diagrama no esta soportada.");
        }
        Set<String> persistentClassIds = persistentClassEndpoints(document);
        List<ApplicationEntity> entities = new ArrayList<>();
        for (Object rawNode : list(document.get("nodes"), "nodes")) {
            Map<?, ?> node = map(rawNode, "nodo");
            String entityId = text(node.get("id"), "ID de entidad");
            Map<?, ?> data = map(node.get("data"), "data de entidad " + entityId);
            if (Integer.valueOf(2).equals(document.get("version")) && data.get("kind") != null
                    && !("ENTITY".equals(data.get("kind"))
                    || "ASSOCIATION_CLASS".equals(data.get("kind")) && data.get("association") != null
                    || "CLASS".equals(data.get("kind")) && persistentClassIds.contains(entityId))) {
                continue;
            }
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

        if (entities.isEmpty()) throw new ApplicationSchemaException(
                "El diagrama no contiene entidades persistentes generables. Agrega al menos un nodo ENTITY con primary key.");
        Set<String> generableEntityIds = new HashSet<>();
        entities.forEach(entity -> generableEntityIds.add(entity.id()));
        List<ApplicationRelationship> relationships = new ArrayList<>();
        for (Object rawEdge : list(document.get("edges"), "edges")) {
            Map<?, ?> edge = map(rawEdge, "relacion");
            String id = text(edge.get("id"), "ID de relacion");
            String source = text(edge.get("source"), "source de " + id);
            String target = text(edge.get("target"), "target de " + id);
            Map<?, ?> data = edge.get("data") == null ? Map.of() : map(edge.get("data"), "data de " + id);
            if (Integer.valueOf(2).equals(document.get("version")) && data.get("relationKind") != null
                    && !"ASSOCIATION".equals(data.get("relationKind"))) continue;
            if (Integer.valueOf(2).equals(document.get("version"))
                    && (!generableEntityIds.contains(source) || !generableEntityIds.contains(target))) continue;
            ApplicationCardinality[] cards = cardinalities(data, id);
            String relationshipName = optionalText(data.get("name"), "nombre de relacion " + id);
            String joinTableName = optionalText(data.get("joinTableName"), "tabla intermedia de " + id);
            relationships.add(new ApplicationRelationship(id, source, target, cards[0], cards[1],
                    relationshipName, joinTableName));
        }

        ApplicationSchema result = new ApplicationSchema(SCHEMA_VERSION, projectName, projectName,
                TechnicalNameNormalizer.typeName(projectName), List.copyOf(entities), List.copyOf(relationships),
                structuralModel(document, persistentClassIds));
        return ApplicationSchemaValidator.validate(result);
    }
    
    private static Set<String> persistentClassEndpoints(
            Map<String, Object> document) {

        if (!Integer.valueOf(2).equals(document.get("version"))) {
            return Set.of();
        }

        Map<String, Map<?, ?>> dataById = new HashMap<>();

        for (Object raw : list(document.get("nodes"), "nodes")) {
            Map<?, ?> node = map(raw, "nodo");
            String id = text(node.get("id"), "ID de nodo");
            Map<?, ?> data = map(node.get("data"), "data de nodo");
            dataById.put(id, data);
        }

        Set<String> persistentIds = new HashSet<>();

        for (Map<?, ?> data : dataById.values()) {
            if (!"ASSOCIATION_CLASS".equals(data.get("kind"))
                    || data.get("association") == null) {
                continue;
            }

            String associationName = text(
                    data.get("name"), "nombre de clase asociativa");

            Map<?, ?> association = map(
                    data.get("association"),
                    "asociacion de " + associationName);

            for (Object rawEndpoint : list(
                    association.get("endpoints"),
                    "endpoints de " + associationName)) {

                Map<?, ?> endpoint = map(
                        rawEndpoint, "extremo de " + associationName);

                String endpointId = text(
                        endpoint.get("entityId"),
                        "entidad referenciada por " + associationName);

                Map<?, ?> endpointData = dataById.get(endpointId);

                if (endpointData == null) {
                    throw new ApplicationSchemaException(
                            "La asociacion " + associationName
                            + " referencia el nodo inexistente "
                            + endpointId + ".");
                }

                String kind = endpointData.get("kind") == null
                        ? "ENTITY"
                        : text(endpointData.get("kind"),
                                "tipo de " + endpointId);

                if ("ENTITY".equals(kind)) {
                    continue;
                }

                if (!"CLASS".equals(kind)) {
                    throw new ApplicationSchemaException(
                            "La asociacion " + associationName
                            + " requiere extremos ENTITY o CLASS persistibles.");
                }

                String name = text(
                        endpointData.get("name"),
                        "nombre de " + endpointId);

                boolean hasPk = false;

                for (Object rawAttribute : list(
                        endpointData.get("attributes"),
                        "atributos de " + name)) {

                    Map<?, ?> attribute = map(
                            rawAttribute, "atributo de " + name);

                    if (Boolean.TRUE.equals(attribute.get("primaryKey"))) {
                        hasPk = true;
                    }
                }

                if (!hasPk) {
                    throw new ApplicationSchemaException(
                            "La clase UML " + name
                            + " participa en " + associationName
                            + " pero no tiene clave primaria para "
                            + "generar una entidad persistente.");
                }

                if (endpointData.get("methods") instanceof List<?> methods
                        && !methods.isEmpty()) {
                    throw new ApplicationSchemaException(
                            "La clase UML " + name
                            + " tiene metodos: conviertela explicitamente "
                            + "a ENTITY antes de generar CRUD.");
                }

                persistentIds.add(endpointId);
            }
        }

        // Los CLASS no participantes en asociaciones siguen siendo solo UML,
        // salvo activacion explicita de CRUD desde el editor.
        for (Map.Entry<String, Map<?, ?>> entry : dataById.entrySet()) {
            Map<?, ?> data = entry.getValue();
            if (!"CLASS".equals(data.get("kind"))) continue;

            Object crudOption = data.get("generateCrud");
            if (crudOption != null && !(crudOption instanceof Boolean)) {
                throw new ApplicationSchemaException(
                        "La opcion Generar CRUD debe ser booleana en " + data.get("name") + ".");
            }
            if (!Boolean.TRUE.equals(crudOption)) continue;

            String name = text(data.get("name"), "nombre de clase UML CRUD");
            if (Boolean.TRUE.equals(data.get("abstract"))) {
                throw new ApplicationSchemaException(
                        "La clase UML " + name + " es abstracta y no puede generar CRUD.");
            }
            if (data.get("methods") instanceof List<?> methods && !methods.isEmpty()) {
                throw new ApplicationSchemaException(
                        "La clase UML " + name + " tiene metodos: no se puede generar CRUD sin perderlos.");
            }
            long pkCount = 0;
            for (Object rawAttribute : list(data.get("attributes"), "atributos de " + name)) {
                Map<?, ?> attribute = map(rawAttribute, "atributo de " + name);
                if (Boolean.TRUE.equals(attribute.get("static"))) {
                    throw new ApplicationSchemaException(
                            "La clase UML " + name + " tiene atributos static; desactiva CRUD o retiralos.");
                }
                if (Boolean.TRUE.equals(attribute.get("primaryKey"))) pkCount++;
            }
            if (pkCount != 1) {
                throw new ApplicationSchemaException(
                        "La clase UML " + name + " necesita exactamente una Primary Key para generar CRUD.");
            }
            persistentIds.add(entry.getKey());
        }

        // No descartar silenciosamente herencia o realización UML.
        for (Object raw : list(document.get("edges"), "edges")) {
            Map<?, ?> edge = map(raw, "relacion");

            Map<?, ?> edgeData = edge.get("data") == null
                    ? Map.of()
                    : map(edge.get("data"), "data de relacion");

            String kind = edgeData.get("relationKind") == null
                    ? "ASSOCIATION"
                    : text(edgeData.get("relationKind"),
                            "tipo de relacion");

            if (("GENERALIZATION".equals(kind)
                    || "REALIZATION".equals(kind))
                    && (persistentIds.contains(edge.get("source"))
                    || persistentIds.contains(edge.get("target")))) {

                throw new ApplicationSchemaException(
                        "Una clase UML utilizada como entidad persistente "
                        + "tiene herencia o realizacion: define primero "
                        + "una conversion explicita para evitar perder "
                        + "esas relaciones.");
            }
        }

        return persistentIds;
    }

    private static UmlStructuralModel structuralModel(
            Map<String, Object> document,
            Set<String> persistentClassIds) {
        if (!Integer.valueOf(2).equals(document.get("version"))) return UmlStructuralModel.EMPTY;
        List<UmlStructuralModel.Classifier> classifiers = new ArrayList<>();
        Set<String> structuralIds = new HashSet<>();
        for (Object rawNode : list(document.get("nodes"), "nodes")) {
            Map<?, ?> node = map(rawNode, "nodo UML");
            String id = text(node.get("id"), "ID de nodo UML");
            Map<?, ?> data = map(node.get("data"), "datos UML de " + id);
            String kind = data.get("kind") == null ? "ENTITY" : text(data.get("kind"), "tipo UML de " + id);
            if ("ENTITY".equals(kind)
                    || persistentClassIds.contains(id)
                    || "ASSOCIATION_CLASS".equals(kind) && data.get("association") != null) {
                continue;
            }
            structuralIds.add(id);
            List<UmlStructuralModel.Member> attributes = new ArrayList<>();
            for (Object raw : list(data.get("attributes"), "atributos UML de " + id)) {
                Map<?, ?> attribute = map(raw, "atributo UML de " + id);
                attributes.add(new UmlStructuralModel.Member(text(attribute.get("name"), "nombre de atributo UML"),
                        text(attribute.get("type"), "tipo de atributo UML"), visibility(attribute.get("visibility")),
                        Boolean.TRUE.equals(attribute.get("static"))));
            }
            List<UmlStructuralModel.Operation> operations = new ArrayList<>();
            for (Object raw : data.get("methods") == null ? List.of() : list(data.get("methods"), "metodos UML de " + id)) {
                Map<?, ?> method = map(raw, "metodo UML de " + id);
                List<UmlStructuralModel.Parameter> parameters = new ArrayList<>();
                for (Object rawParameter : list(method.get("parameters"), "parametros UML")) {
                    Map<?, ?> parameter = map(rawParameter, "parametro UML");
                    parameters.add(new UmlStructuralModel.Parameter(text(parameter.get("name"), "nombre de parametro UML"),
                            text(parameter.get("type"), "tipo de parametro UML")));
                }
                operations.add(new UmlStructuralModel.Operation(text(method.get("name"), "nombre de metodo UML"),
                        text(method.get("returnType"), "retorno de metodo UML"), visibility(method.get("visibility")),
                        Boolean.TRUE.equals(method.get("static")), Boolean.TRUE.equals(method.get("abstract")),
                        List.copyOf(parameters)));
            }
            List<String> literals = new ArrayList<>();
            for (Object raw : data.get("literals") == null ? List.of() : list(data.get("literals"), "literales UML de " + id))
                literals.add(text(raw, "literal UML"));
            classifiers.add(new UmlStructuralModel.Classifier(id, text(data.get("name"), "nombre UML"), kind,
                    Boolean.TRUE.equals(data.get("abstract")), List.copyOf(attributes), List.copyOf(operations),
                    List.copyOf(literals)));
        }
        List<UmlStructuralModel.Relation> relations = new ArrayList<>();
        for (Object rawEdge : list(document.get("edges"), "edges")) {
            Map<?, ?> edge = map(rawEdge, "relacion UML");
            String source = text(edge.get("source"), "origen UML"), target = text(edge.get("target"), "destino UML");
            Map<?, ?> data = edge.get("data") == null ? Map.of() : map(edge.get("data"), "datos de relacion UML");
            String kind = data.get("relationKind") == null ? "ASSOCIATION" : text(data.get("relationKind"), "tipo de relacion UML");
            if (("GENERALIZATION".equals(kind) || "REALIZATION".equals(kind))
                    && structuralIds.contains(source) && structuralIds.contains(target)) {
                relations.add(new UmlStructuralModel.Relation(text(edge.get("id"), "ID de relacion UML"), source, target, kind));
            }
        }
        return new UmlStructuralModel(List.copyOf(classifiers), List.copyOf(relations));
    }

    private static String visibility(Object raw) {
        return raw instanceof String value && Set.of("public", "private", "protected", "package").contains(value)
                ? value : "private";
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
