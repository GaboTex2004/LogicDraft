package com.sw1.backend.generator.ea;

import com.sw1.backend.ai.diagram.model.DiagramCardinality;
import com.sw1.backend.generator.ea.NormalizedDiagram.NormalizedAssociation;
import com.sw1.backend.generator.ea.NormalizedDiagram.NormalizedAttribute;
import com.sw1.backend.generator.ea.NormalizedDiagram.NormalizedClass;
import com.sw1.backend.generator.ea.NormalizedDiagram.NormalizedGeneralization;
import com.sw1.backend.generator.ea.NormalizedDiagram.NormalizedDependency;
import com.sw1.backend.generator.ea.NormalizedDiagram.NormalizedOperation;
import com.sw1.backend.generator.ea.NormalizedDiagram.NormalizedParameter;
import com.sw1.backend.generator.ea.NormalizedDiagram.NormalizedRealization;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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

        List<String> warnings = new ArrayList<>(diagram.warnings());
        List<Map<String, Object>> nodes = new ArrayList<>();
        for (NormalizedClass item : diagram.classes()) {
            String nodeId = nodeIds.get(item.externalId());
            NormalizedAssociation association = associationByClass.get(item.externalId());
            List<Map<String, Object>> attributes = attributes(item);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("id", nodeId);
            data.put("name", item.name());
            data.put("kind", item.kind());
            data.put("attributes", attributes);
            data.put("methods", methods(item));
            data.put("literals", item.literals());
            data.put("abstract", item.abstractType());
            data.put("width", item.size().width());
            data.put("height", item.size().height());
            data.put("externalMetadata", externalMetadata(item.externalId()));

            if (association != null) {
                NormalizedClass source = requiredClass(classes, association.sourceExternalId());
                NormalizedClass target = requiredClass(classes, association.targetExternalId());
                String sourceEdgeId = structuralEdgeId(association.externalId(), "SOURCE");
                String targetEdgeId = structuralEdgeId(association.externalId(), "TARGET");
                Map<String, Object> associationData = new LinkedHashMap<>();
                associationData.put("kind", "MANY_TO_MANY_ASSOCIATION");
                associationData.put("tableName", sqlName(technicalName(item.name())));
                associationData.put("uniquePair", true);
                associationData.put("externalMetadata", externalMetadata(association.externalId()));
                associationData.put("sourceMultiplicity", association.sourceMultiplicity().name());
                associationData.put("targetMultiplicity", association.targetMultiplicity().name());
                associationData.put("endpoints", List.of(
                                Map.of("role", "SOURCE", "entityId", nodeIds.get(source.externalId()),
                                        "relationshipId", sourceEdgeId,
                                        "foreignKeyName", foreignKeyName(source.name())),
                                Map.of("role", "TARGET", "entityId", nodeIds.get(target.externalId()),
                                        "relationshipId", targetEdgeId,
                                        "foreignKeyName", foreignKeyName(target.name()))
                        ));
                data.put("association", associationData);
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
                        association.sourceMultiplicity(), association.targetMultiplicity(), association.name(),
                        association.relationKind(), association.aggregationEnd()));
                continue;
            }
            String associationNodeId = nodeIds.get(association.associationClassExternalId());
            // This is intentionally identical to convertManyToManyAssociation in React.
            edges.add(edge(structuralEdgeId(association.externalId(), "SOURCE"),
                    association.externalId() + ":SOURCE",
                    nodeIds.get(association.sourceExternalId()), associationNodeId,
                    DiagramCardinality.ONE_ONE, association.targetMultiplicity(), null, "ASSOCIATION", null));
            edges.add(edge(structuralEdgeId(association.externalId(), "TARGET"),
                    association.externalId() + ":TARGET",
                    nodeIds.get(association.targetExternalId()), associationNodeId,
                    DiagramCardinality.ONE_ONE, association.sourceMultiplicity(), null, "ASSOCIATION", null));
        }
        for (NormalizedGeneralization generalization : diagram.generalizations()) {
            requiredClass(classes, generalization.specificExternalId());
            requiredClass(classes, generalization.generalExternalId());
            edges.add(typedEdge(generalization.externalId(), nodeIds.get(generalization.specificExternalId()),
                    nodeIds.get(generalization.generalExternalId()), "GENERALIZATION", null));
        }
        for (NormalizedRealization realization : diagram.realizations()) {
            edges.add(typedEdge(realization.externalId(), nodeIds.get(realization.implementingExternalId()),
                    nodeIds.get(realization.interfaceExternalId()), "REALIZATION", null));
        }
        for (NormalizedDependency dependency : diagram.dependencies()) {
            edges.add(typedEdge(dependency.externalId(), nodeIds.get(dependency.clientExternalId()),
                    nodeIds.get(dependency.supplierExternalId()), "DEPENDENCY", dependency.name()));
        }
        return new EnterpriseArchitectImportPreview(
                diagram.projectName(), 2,
                List.copyOf(nodes), List.copyOf(edges), List.copyOf(warnings));
    }

    private static List<Map<String, Object>> attributes(NormalizedClass owner) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (NormalizedAttribute item : owner.attributes()) {
            Map<String, Object> mapped = attribute(stableId("attribute-", item.externalId()), item.externalId(),
                    item.name(), item.type(), item.primaryKey(), item.nullable());
            mapped.put("visibility", normalizeVisibility(item.visibility()));
            mapped.put("static", item.staticAttribute());
            if (item.initialValue() != null) mapped.put("initialValue", item.initialValue());
            result.add(mapped);
        }
        return result;
    }

    private static List<Map<String, Object>> methods(NormalizedClass owner) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (NormalizedOperation operation : owner.operations()) {
            List<Map<String, Object>> parameters = new ArrayList<>();
            for (NormalizedParameter parameter : operation.parameters()) {
                parameters.add(new LinkedHashMap<>(Map.of(
                        "id", stableId("parameter-", parameter.externalId()),
                        "name", parameter.name(),
                        "type", parameter.type())));
            }
            Map<String, Object> mapped = new LinkedHashMap<>();
            mapped.put("id", stableId("method-", operation.externalId()));
            mapped.put("name", operation.name());
            mapped.put("returnType", operation.returnType());
            mapped.put("visibility", normalizeVisibility(operation.visibility()));
            mapped.put("static", operation.staticOperation());
            mapped.put("abstract", operation.abstractOperation());
            mapped.put("parameters", List.copyOf(parameters));
            mapped.put("externalMetadata", externalMetadata(operation.externalId()));
            result.add(mapped);
        }
        return List.copyOf(result);
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
            DiagramCardinality sourceCardinality, DiagramCardinality targetCardinality, String name,
            String relationKind, String aggregationEnd) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("relationKind", relationKind);
        if (aggregationEnd != null) data.put("aggregationEnd", aggregationEnd);
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

    private static Map<String, Object> typedEdge(String externalId, String source, String target,
            String relationKind, String name) {
        if (source == null || target == null) {
            throw new IllegalArgumentException("La relación " + externalId + " referencia un clasificador inexistente.");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("relationKind", relationKind);
        data.put("externalMetadata", externalMetadata(externalId));
        if (name != null && !name.isBlank()) data.put("name", name);
        Map<String, Object> edge = new LinkedHashMap<>();
        edge.put("id", stableId("relationship-", externalId));
        edge.put("type", "relationship");
        edge.put("source", source);
        edge.put("target", target);
        edge.put("data", data);
        return edge;
    }

    private static String normalizeVisibility(String value) {
        if (value == null) return "private";
        String normalized = value.toLowerCase(Locale.ROOT);
        return Set.of("public", "private", "protected", "package").contains(normalized)
                ? normalized : "private";
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
