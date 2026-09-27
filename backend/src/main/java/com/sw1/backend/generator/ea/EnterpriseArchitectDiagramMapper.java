package com.sw1.backend.generator.ea;

import com.sw1.backend.ai.diagram.model.DiagramCardinality;
import com.sw1.backend.generator.ea.NormalizedDiagram.NormalizedAssociation;
import com.sw1.backend.generator.ea.NormalizedDiagram.NormalizedAttribute;
import com.sw1.backend.generator.ea.NormalizedDiagram.NormalizedClass;
import com.sw1.backend.generator.ea.NormalizedDiagram.NormalizedGeneralization;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Maps normalized UML semantics to the same document contract used by the manual editor. */
public final class EnterpriseArchitectDiagramMapper {
    private EnterpriseArchitectDiagramMapper() {}

    public static EnterpriseArchitectImportPreview toPreview(NormalizedDiagram diagram) {
        Map<String, NormalizedClass> classes = new LinkedHashMap<>();
        Map<String, String> nodeIds = new HashMap<>();
        for (NormalizedClass item : diagram.classes()) {
            classes.put(item.externalId(), item);
            nodeIds.put(item.externalId(), stableId("entity-", item.externalId()));
        }

        Map<String, NormalizedAssociation> associationByClass = new HashMap<>();
        for (NormalizedAssociation association : diagram.associations()) {
            if (association.associationClassExternalId() != null) {
                associationByClass.put(association.associationClassExternalId(), association);
            }
        }

        List<Map<String, Object>> nodes = new ArrayList<>();
        for (NormalizedClass item : diagram.classes()) {
            String nodeId = nodeIds.get(item.externalId());
            List<Map<String, Object>> attributes = attributes(item);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("id", nodeId);
            data.put("name", item.name());
            data.put("attributes", attributes);
            data.put("externalMetadata", externalMetadata(item.externalId()));

            NormalizedAssociation association = associationByClass.get(item.externalId());
            if (association != null) {
                NormalizedClass source = requiredClass(classes, association.sourceExternalId());
                NormalizedClass target = requiredClass(classes, association.targetExternalId());
                String sourceEdgeId = structuralEdgeId(association.externalId(), "SOURCE");
                String targetEdgeId = structuralEdgeId(association.externalId(), "TARGET");
                data.put("association", Map.of(
                        "kind", "MANY_TO_MANY_ASSOCIATION",
                        "tableName", sqlName(technicalName(item.name())),
                        "uniquePair", true,
                        "endpoints", List.of(
                                Map.of("role", "SOURCE", "entityId", nodeIds.get(source.externalId()),
                                        "relationshipId", sourceEdgeId,
                                        "foreignKeyName", foreignKeyName(source.name())),
                                Map.of("role", "TARGET", "entityId", nodeIds.get(target.externalId()),
                                        "relationshipId", targetEdgeId,
                                        "foreignKeyName", foreignKeyName(target.name()))
                        )
                ));
            }

            Map<String, Object> node = new LinkedHashMap<>();
            node.put("id", nodeId);
            node.put("type", "entity");
            node.put("position", Map.of("x", item.position().x(), "y", item.position().y()));
            node.put("data", data);
            nodes.add(node);
        }

        List<Map<String, Object>> edges = new ArrayList<>();
        for (NormalizedAssociation association : diagram.associations()) {
            if (association.associationClassExternalId() == null) {
                edges.add(edge(stableId("relationship-", association.externalId()),
                        association.externalId(),
                        nodeIds.get(association.sourceExternalId()), nodeIds.get(association.targetExternalId()),
                        association.sourceMultiplicity(), association.targetMultiplicity(), association.name()));
                continue;
            }
            String associationNodeId = nodeIds.get(association.associationClassExternalId());
            // This is intentionally identical to convertManyToManyAssociation in React.
            edges.add(edge(structuralEdgeId(association.externalId(), "SOURCE"),
                    association.externalId() + ":SOURCE",
                    nodeIds.get(association.sourceExternalId()), associationNodeId,
                    DiagramCardinality.ONE_ONE, association.targetMultiplicity(), null));
            edges.add(edge(structuralEdgeId(association.externalId(), "TARGET"),
                    association.externalId() + ":TARGET",
                    nodeIds.get(association.targetExternalId()), associationNodeId,
                    DiagramCardinality.ONE_ONE, association.sourceMultiplicity(), null));
        }
        List<String> warnings = new ArrayList<>(diagram.warnings());
        for (NormalizedGeneralization generalization : diagram.generalizations()) {
            NormalizedClass specific = requiredClass(classes, generalization.specificExternalId());
            NormalizedClass general = requiredClass(classes, generalization.generalExternalId());
            warnings.add("La generalización " + specific.name() + " → " + general.name()
                    + " no tiene representación en el editor actual y fue omitida.");
        }
        return new EnterpriseArchitectImportPreview(
                diagram.projectName(), 1, List.copyOf(nodes), List.copyOf(edges), List.copyOf(warnings));
    }

    private static List<Map<String, Object>> attributes(NormalizedClass owner) {
        List<Map<String, Object>> result = new ArrayList<>();
        boolean hasValidPk = owner.attributes().stream().anyMatch(attribute -> attribute.primaryKey()
                && !attribute.nullable() && ("INTEGER".equals(attribute.type()) || "BIGINT".equals(attribute.type())));
        NormalizedAttribute promotableId = owner.associationClass() && !hasValidPk
                ? owner.attributes().stream().filter(attribute -> attribute.name().equalsIgnoreCase("id")
                        && ("INTEGER".equals(attribute.type()) || "BIGINT".equals(attribute.type())))
                        .findFirst().orElse(null)
                : null;
        if (owner.associationClass() && !hasValidPk && promotableId == null) {
            result.add(attribute(stableId("attribute-", owner.externalId() + ":generated-pk"),
                    owner.externalId() + ":generated-pk", "id", "INTEGER", true, false));
        }
        for (NormalizedAttribute item : owner.attributes()) {
            boolean primaryKey = item.primaryKey() || item == promotableId;
            result.add(attribute(stableId("attribute-", item.externalId()), item.externalId(), item.name(), item.type(),
                    primaryKey, primaryKey ? false : item.nullable()));
        }
        return result;
    }

    private static Map<String, Object> attribute(String id, String externalId, String name, String type,
            boolean primaryKey, boolean nullable) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", id);
        result.put("name", name);
        result.put("type", type);
        result.put("primaryKey", primaryKey);
        result.put("nullable", nullable);
        result.put("externalMetadata", externalMetadata(externalId));
        return result;
    }

    private static Map<String, Object> edge(String id, String externalId, String source, String target,
            DiagramCardinality sourceCardinality, DiagramCardinality targetCardinality, String name) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sourceCardinality", sourceCardinality.name());
        data.put("targetCardinality", targetCardinality.name());
        data.put("externalMetadata", externalMetadata(externalId));
        if (name != null && !name.isBlank()) data.put("name", name);
        Map<String, Object> edge = new LinkedHashMap<>();
        edge.put("id", id);
        edge.put("type", "relationship");
        edge.put("source", source);
        edge.put("target", target);
        edge.put("data", data);
        return edge;
    }

    private static NormalizedClass requiredClass(Map<String, NormalizedClass> classes, String id) {
        NormalizedClass item = classes.get(id);
        if (item == null) throw new IllegalArgumentException("La asociación referencia una clase inexistente: " + id);
        return item;
    }

    private static String structuralEdgeId(String associationId, String role) {
        return stableId("relationship-", associationId + ":" + role);
    }

    private static String foreignKeyName(String entityName) {
        return sqlName(technicalName(entityName) + "Id");
    }

    private static String technicalName(String value) {
        String cleaned = Normalizer.normalize(value.trim(), Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .replaceAll("([a-z0-9])([A-Z])", "$1 $2")
                .replaceAll("[^A-Za-z0-9]+", " ").trim();
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

    static String stableId(String prefix, String source) {
        return prefix + UUID.nameUUIDFromBytes(source.getBytes(StandardCharsets.UTF_8));
    }

    private static Map<String, Object> externalMetadata(String externalId) {
        return Map.of("source", "enterprise-architect", "externalId", externalId);
    }
}
