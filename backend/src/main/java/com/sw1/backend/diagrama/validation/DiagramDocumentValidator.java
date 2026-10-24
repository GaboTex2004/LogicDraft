package com.sw1.backend.diagrama.validation;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Validates persisted diagram invariants without rewriting the client document. */
public final class DiagramDocumentValidator {
    private static final Set<String> ATTRIBUTE_TYPES = Set.of(
            "BIGINT", "INTEGER", "VARCHAR", "TEXT", "BOOLEAN", "DATE", "TIMESTAMP", "DECIMAL");
    private static final Set<String> CARDINALITIES = Set.of("ZERO_ONE", "ONE_ONE", "ZERO_MANY", "ONE_MANY");
    private static final Set<String> LEGACY_RELATIONSHIPS = Set.of(
            "ONE_TO_ONE", "ONE_TO_MANY", "MANY_TO_ONE", "MANY_TO_MANY");

    private DiagramDocumentValidator() {}

    public static void validate(Map<String, Object> document) {
        if (document == null || !Integer.valueOf(1).equals(document.get("version")))
            fail("La version del documento no esta soportada.");

        List<?> nodes = list(document.get("nodes"), "nodes");
        List<?> edges = list(document.get("edges"), "edges");
        Set<String> nodeIds = new HashSet<>();
        Set<String> entityNames = new HashSet<>();
        Map<String, Map<?, ?>> nodesById = new HashMap<>();

        for (Object raw : nodes) {
            Map<?, ?> node = map(raw, "entidad");
            String id = text(node.get("id"), "ID de entidad");
            if (!nodeIds.add(id)) fail("El diagrama contiene IDs de entidad duplicados.");
            Object nodeType = node.get("type");
            if (nodeType != null && !"entity".equals(nodeType)) fail("El diagrama contiene un tipo de nodo no soportado.");
            validatePosition(node.get("position"));
            Map<?, ?> data = map(node.get("data"), "datos de entidad " + id);
            String dataId = optionalText(data.get("id"), "ID semantico de entidad");
            if (dataId != null && !id.equals(dataId)) fail("El ID visual y semantico de una entidad no coinciden.");
            String name = text(data.get("name"), "nombre de entidad");
            if (!entityNames.add(key(name))) fail("El diagrama contiene nombres de entidad duplicados.");
            validateAttributes(name, list(data.get("attributes"), "atributos de " + name));
            validateExternalMetadata(data.get("externalMetadata"));
            nodesById.put(id, node);
        }

        Set<String> edgeIds = new HashSet<>();
        Map<String, Map<?, ?>> edgesById = new HashMap<>();
        for (Object raw : edges) {
            Map<?, ?> edge = map(raw, "relacion");
            String id = optionalText(edge.get("id"), "ID de relacion");
            // Compatibility: early persisted diagrams did not require edge IDs.
            if (id != null && !edgeIds.add(id)) fail("El diagrama contiene IDs de relacion duplicados.");
            String source = text(edge.get("source"), "origen de relacion");
            String target = text(edge.get("target"), "destino de relacion");
            if (!nodeIds.contains(source) || !nodeIds.contains(target))
                fail("El diagrama contiene una relacion con extremos inexistentes.");
            if (source.equals(target)) fail("Las autorrelaciones no estan soportadas.");
            Object edgeType = edge.get("type");
            if (edgeType != null && !"relationship".equals(edgeType))
                fail("El diagrama contiene un tipo de relacion no soportado.");
            validateRelationshipData(edge.get("data"));
            if (id != null) edgesById.put(id, edge);
        }

        for (Map.Entry<String, Map<?, ?>> entry : nodesById.entrySet()) {
            Map<?, ?> data = map(entry.getValue().get("data"), "datos de entidad");
            if (data.get("association") != null)
                validateAssociation(entry.getKey(), map(data.get("association"), "entidad asociativa"),
                        nodeIds, edgesById);
        }
    }

    private static void validateAttributes(String entityName, List<?> attributes) {
        Set<String> ids = new HashSet<>();
        Set<String> names = new HashSet<>();
        for (Object raw : attributes) {
            Map<?, ?> attribute = map(raw, "atributo de " + entityName);
            String id = text(attribute.get("id"), "ID de atributo");
            String name = text(attribute.get("name"), "nombre de atributo");
            if (!ids.add(id) || !names.add(key(name)))
                fail("La entidad " + entityName + " contiene atributos duplicados.");
            String type = text(attribute.get("type"), "tipo de atributo").toUpperCase(Locale.ROOT);
            if (!ATTRIBUTE_TYPES.contains(type)) fail("El atributo " + entityName + "." + name + " usa un tipo no soportado.");
            boolean primaryKey = bool(attribute.get("primaryKey"), "primaryKey de atributo");
            if (attribute.containsKey("nullable")) {
                boolean nullable = bool(attribute.get("nullable"), "nullable de atributo");
                if (primaryKey && nullable) fail("Una clave primaria no puede aceptar null.");
            }
            validateExternalMetadata(attribute.get("externalMetadata"));
        }
    }

    private static void validatePosition(Object raw) {
        // Compatibility: position was optional in the first persisted format.
        if (raw == null) return;
        Map<?, ?> position = map(raw, "posicion de entidad");
        number(position.get("x"), "coordenada x");
        number(position.get("y"), "coordenada y");
    }

    private static void validateRelationshipData(Object raw) {
        if (raw == null) return;
        Map<?, ?> data = map(raw, "datos de relacion");
        boolean hasSource = data.containsKey("sourceCardinality");
        boolean hasTarget = data.containsKey("targetCardinality");
        if (hasSource != hasTarget) fail("La relacion debe declarar ambas cardinalidades.");
        if (hasSource) {
            if (!CARDINALITIES.contains(text(data.get("sourceCardinality"), "cardinalidad de origen"))
                    || !CARDINALITIES.contains(text(data.get("targetCardinality"), "cardinalidad de destino")))
                fail("La relacion contiene una cardinalidad no soportada.");
        } else if (data.get("relationshipType") != null
                && !LEGACY_RELATIONSHIPS.contains(text(data.get("relationshipType"), "tipo de relacion heredado"))) {
            fail("La relacion heredada contiene un tipo no soportado.");
        }
        optionalText(data.get("name"), "nombre de relacion");
        optionalText(data.get("joinTableName"), "tabla intermedia");
        validateExternalMetadata(data.get("externalMetadata"));
    }

    private static void validateAssociation(String associationNodeId, Map<?, ?> association,
            Set<String> nodeIds, Map<String, Map<?, ?>> edgesById) {
        if (!"MANY_TO_MANY_ASSOCIATION".equals(association.get("kind"))
                || !Boolean.TRUE.equals(association.get("uniquePair")))
            fail("La entidad asociativa contiene metadatos no soportados.");
        text(association.get("tableName"), "tabla de entidad asociativa");
        List<?> endpoints = list(association.get("endpoints"), "extremos de entidad asociativa");
        if (endpoints.size() != 2) fail("La entidad asociativa debe tener exactamente dos extremos.");
        Set<String> roles = new HashSet<>();
        Set<String> entities = new HashSet<>();
        Set<String> relationships = new HashSet<>();
        for (Object raw : endpoints) {
            Map<?, ?> endpoint = map(raw, "extremo de entidad asociativa");
            String role = text(endpoint.get("role"), "rol de extremo");
            if (!Set.of("SOURCE", "TARGET").contains(role) || !roles.add(role))
                fail("La entidad asociativa contiene roles invalidos o duplicados.");
            String entityId = text(endpoint.get("entityId"), "entidad de extremo");
            String relationshipId = text(endpoint.get("relationshipId"), "relacion estructural");
            text(endpoint.get("foreignKeyName"), "foreign key de extremo");
            if (!nodeIds.contains(entityId) || entityId.equals(associationNodeId) || !entities.add(entityId))
                fail("La entidad asociativa referencia extremos invalidos.");
            Map<?, ?> edge = edgesById.get(relationshipId);
            if (edge == null || !relationships.add(relationshipId))
                fail("La entidad asociativa referencia relaciones estructurales inexistentes.");
            String source = (String) edge.get("source");
            String target = (String) edge.get("target");
            if (!(source.equals(associationNodeId) && target.equals(entityId)
                    || target.equals(associationNodeId) && source.equals(entityId)))
                fail("La relacion estructural no coincide con la entidad asociativa.");
        }
    }

    private static void validateExternalMetadata(Object raw) {
        if (raw == null) return;
        Map<?, ?> metadata = map(raw, "metadata externa");
        if (!"enterprise-architect".equals(metadata.get("source"))) fail("La metadata externa no esta soportada.");
        text(metadata.get("externalId"), "ID externo");
    }

    private static Map<?, ?> map(Object value, String subject) {
        if (!(value instanceof Map<?, ?> result)) fail("La estructura de " + subject + " no es valida.");
        return (Map<?, ?>) value;
    }

    private static List<?> list(Object value, String subject) {
        if (!(value instanceof List<?> result)) fail("La estructura de " + subject + " no es valida.");
        return (List<?>) value;
    }

    private static String text(Object value, String subject) {
        if (!(value instanceof String result) || result.isBlank() || !result.equals(result.strip()) || result.length() > 100)
            fail(subject + " no es valido.");
        return (String) value;
    }

    private static String optionalText(Object value, String subject) {
        return value == null ? null : text(value, subject);
    }

    private static boolean bool(Object value, String subject) {
        if (!(value instanceof Boolean result)) fail(subject + " no es valido.");
        return (Boolean) value;
    }

    private static void number(Object value, String subject) {
        if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue())) fail(subject + " no es valida.");
    }

    private static String key(String value) {
        return value.strip().toLowerCase(Locale.ROOT);
    }

    private static void fail(String message) {
        throw new DiagramValidationException(message);
    }
}
