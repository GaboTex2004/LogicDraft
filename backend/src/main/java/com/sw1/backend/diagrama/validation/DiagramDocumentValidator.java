package com.sw1.backend.diagrama.validation;

import java.text.Normalizer;
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
    private static final Set<String> UML_NODE_KINDS = Set.of(
            "ENTITY", "CLASS", "INTERFACE", "ENUMERATION", "ASSOCIATION_CLASS");
    private static final Set<String> UML_RELATION_KINDS = Set.of(
            "ASSOCIATION", "GENERALIZATION", "REALIZATION", "AGGREGATION", "COMPOSITION", "DEPENDENCY");
    private static final Set<String> UML_VISIBILITIES = Set.of("public", "private", "protected", "package");

    private DiagramDocumentValidator() {}

    public static void validate(Map<String, Object> document) {
        if (document == null || !(Integer.valueOf(1).equals(document.get("version"))
                || Integer.valueOf(2).equals(document.get("version"))))
            fail("La version del documento no esta soportada.");
        boolean umlV2 = Integer.valueOf(2).equals(document.get("version"));

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
            if (umlV2) validateUmlNode(data, name);
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
            if (umlV2) validateUmlRelationship(edge.get("data"), source, target, nodesById);
            if (id != null) edgesById.put(id, edge);
        }

        for (Map.Entry<String, Map<?, ?>> entry : nodesById.entrySet()) {
            Map<?, ?> data = map(entry.getValue().get("data"), "datos de entidad");
            if (data.get("association") != null)
                validateAssociation(entry.getKey(), data, map(data.get("association"), "entidad asociativa"),
                        nodeIds, edgesById);
        }
    }

    private static void validateUmlNode(Map<?, ?> data, String name) {
        String kind = data.get("kind") == null ? "ENTITY" : text(data.get("kind"), "tipo UML");
        if (!UML_NODE_KINDS.contains(kind)) fail("El nodo " + name + " usa un tipo UML no soportado.");
        if (data.get("abstract") != null) bool(data.get("abstract"), "abstract de " + name);
        if (data.get("width") != null) number(data.get("width"), "ancho de " + name);
        if (data.get("height") != null) number(data.get("height"), "alto de " + name);
        Set<String> methodIds = new HashSet<>();
        for (Object raw : data.get("methods") == null ? List.of() : list(data.get("methods"), "metodos de " + name)) {
            Map<?, ?> method = map(raw, "metodo de " + name);
            if (!methodIds.add(text(method.get("id"), "ID de metodo"))) fail("El nodo contiene IDs de metodo duplicados.");
            text(method.get("name"), "nombre de metodo");
            if (!UML_VISIBILITIES.contains(text(method.get("visibility"), "visibilidad de metodo"))) fail("Visibilidad UML no soportada.");
            text(method.get("returnType"), "tipo de retorno");
            boolean staticMethod = method.get("static") != null && bool(method.get("static"), "static de metodo");
            boolean abstractMethod = method.get("abstract") != null && bool(method.get("abstract"), "abstract de metodo");
            if (staticMethod && abstractMethod) fail("El metodo " + name + "." + method.get("name") + " no puede ser static y abstract.");
            Set<String> parameterIds = new HashSet<>();
            for (Object parameterRaw : list(method.get("parameters"), "parametros de metodo")) {
                Map<?, ?> parameter = map(parameterRaw, "parametro");
                if (!parameterIds.add(text(parameter.get("id"), "ID de parametro"))) fail("El metodo contiene IDs de parametro duplicados.");
                text(parameter.get("name"), "nombre de parametro");
                text(parameter.get("type"), "tipo de parametro");
            }
        }
        if (data.get("literals") != null) {
            Set<String> literals = new HashSet<>();
            for (Object literal : list(data.get("literals"), "literales de " + name))
                if (!literals.add(key(text(literal, "literal")))) fail("La enumeracion contiene literales duplicados.");
            if (!"ENUMERATION".equals(kind) && !literals.isEmpty()) fail("Solo una enumeracion puede declarar literales.");
        }
        if ("ENUMERATION".equals(kind) && !list(data.get("attributes"), "atributos de enumeracion").isEmpty())
            fail("La enumeracion " + name + " debe usar literales, no atributos.");
    }

    private static void validateUmlRelationship(Object raw, String source, String target,
            Map<String, Map<?, ?>> nodesById) {
        if (raw == null) return;
        Map<?, ?> data = map(raw, "datos de relacion UML");
        if (data.get("relationKind") == null) return;
        String kind = text(data.get("relationKind"), "tipo de relacion UML");
        if (!UML_RELATION_KINDS.contains(kind)) fail("La relacion UML no esta soportada.");
        String sourceKind = nodeKind(nodesById.get(source));
        String targetKind = nodeKind(nodesById.get(target));
        if ("REALIZATION".equals(kind) && (!classLike(sourceKind) || !"INTERFACE".equals(targetKind)))
            fail("Una realizacion debe partir de una clase o entidad y terminar en una interfaz.");
        if ("GENERALIZATION".equals(kind) && !(classLike(sourceKind) && classLike(targetKind)
                || "INTERFACE".equals(sourceKind) && "INTERFACE".equals(targetKind)))
            fail("Una generalizacion debe conectar clases entre si o interfaces entre si.");
        if (("AGGREGATION".equals(kind) || "COMPOSITION".equals(kind))
                && (!classLike(sourceKind) || !classLike(targetKind)))
            fail("Una agregacion o composicion solo puede conectar clases o entidades.");
        String aggregationEnd = optionalText(data.get("aggregationEnd"), "extremo de agregacion");
        if (aggregationEnd != null && (!Set.of("SOURCE", "TARGET").contains(aggregationEnd)
                || !("AGGREGATION".equals(kind) || "COMPOSITION".equals(kind))))
            fail("El extremo de agregacion no coincide con el tipo de relacion.");
        if (!("ASSOCIATION".equals(kind) || "AGGREGATION".equals(kind) || "COMPOSITION".equals(kind))
                && (data.get("sourceMultiplicity") != null || data.get("targetMultiplicity") != null))
            fail("La relacion " + kind + " no admite multiplicidades.");
        optionalText(data.get("sourceMultiplicity"), "multiplicidad de origen");
        optionalText(data.get("targetMultiplicity"), "multiplicidad de destino");
    }

    private static boolean classLike(String kind) {
        return Set.of("ENTITY", "CLASS", "ASSOCIATION_CLASS").contains(kind);
    }

    private static String nodeKind(Map<?, ?> node) {
        Map<?, ?> data = map(node.get("data"), "datos de nodo UML");
        return data.get("kind") == null ? "ENTITY" : text(data.get("kind"), "tipo UML");
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

    private static void validateAssociation(String associationNodeId, Map<?, ?> nodeData, Map<?, ?> association,
            Set<String> nodeIds, Map<String, Map<?, ?>> edgesById) {
        if (!"MANY_TO_MANY_ASSOCIATION".equals(association.get("kind"))
                || !Boolean.TRUE.equals(association.get("uniquePair")))
            fail("La entidad asociativa contiene metadatos no soportados.");
        text(association.get("tableName"), "tabla de entidad asociativa");
        for (String field : List.of("sourceMultiplicity", "targetMultiplicity")) {
            if (association.get(field) != null
                    && !CARDINALITIES.contains(text(association.get(field), "multiplicidad de entidad asociativa")))
                fail("La entidad asociativa contiene una multiplicidad no soportada.");
        }
        validateExternalMetadata(association.get("externalMetadata"));
        List<?> endpoints = list(association.get("endpoints"), "extremos de entidad asociativa");
        if (endpoints.size() != 2) fail("La entidad asociativa debe tener exactamente dos extremos.");
        Set<String> roles = new HashSet<>();
        Set<String> entities = new HashSet<>();
        Set<String> relationships = new HashSet<>();
        Set<String> foreignKeys = new HashSet<>();
        boolean importedAssociation = eaMetadata(association.get("externalMetadata"));
        for (Object raw : endpoints) {
            Map<?, ?> endpoint = map(raw, "extremo de entidad asociativa");
            String role = text(endpoint.get("role"), "rol de extremo");
            if (!Set.of("SOURCE", "TARGET").contains(role) || !roles.add(role))
                fail("La entidad asociativa contiene roles invalidos o duplicados.");
            String entityId = text(endpoint.get("entityId"), "entidad de extremo");
            String relationshipId = text(endpoint.get("relationshipId"), "relacion estructural");
            foreignKeys.add(key(text(endpoint.get("foreignKeyName"), "foreign key de extremo")));
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
        for (Object raw : list(nodeData.get("attributes"), "atributos de entidad asociativa")) {
            Map<?, ?> attribute = map(raw, "atributo de entidad asociativa");
            String name = text(attribute.get("name"), "nombre de atributo de entidad asociativa");
            if (foreignKeys.contains(key(sqlName(technicalName(name))))
                    && !(importedAssociation && eaMetadata(attribute.get("externalMetadata"))))
                fail("El atributo " + name + " colisiona con una clave foranea estructural.");
        }
    }

    private static void validateExternalMetadata(Object raw) {
        if (raw == null) return;
        Map<?, ?> metadata = map(raw, "metadata externa");
        if (!"enterprise-architect".equals(metadata.get("source"))) fail("La metadata externa no esta soportada.");
        text(metadata.get("externalId"), "ID externo");
    }

    private static boolean eaMetadata(Object raw) {
        return raw instanceof Map<?, ?> metadata
                && "enterprise-architect".equals(metadata.get("source"))
                && metadata.get("externalId") instanceof String id && !id.isBlank();
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

    private static String technicalName(String value) {
        String cleaned = Normalizer.normalize(value.strip(), Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .replaceAll("([a-z0-9])([A-Z])", "$1 $2")
                .replaceAll("[^A-Za-z0-9]+", " ").strip();
        StringBuilder result = new StringBuilder();
        for (String word : cleaned.split(" +")) {
            if (!word.isEmpty()) result.append(word.substring(0, 1).toUpperCase(Locale.ROOT))
                    .append(word.substring(1).toLowerCase(Locale.ROOT));
        }
        if (!result.isEmpty() && Character.isDigit(result.charAt(0))) result.insert(0, 'N');
        return result.toString();
    }

    private static String sqlName(String value) {
        return value.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
    }

    private static void fail(String message) {
        throw new DiagramValidationException(message);
    }
}
