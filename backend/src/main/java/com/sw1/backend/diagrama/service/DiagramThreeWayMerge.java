package com.sw1.backend.diagrama.service;

import com.sw1.backend.diagrama.validation.DiagramDocumentValidator;
import com.sw1.backend.diagrama.validation.DiagramValidationException;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * Deterministic element-level three-way merge for UML v1/v2 diagram documents.
 *
 * <p>Merges {@code incoming} (authored against {@code base}) onto {@code current}
 * by stable {@code node.id} and {@code edge.id}. Any overlapping incompatible
 * modification on the same element or cross-element UML validation failure
 * aborts the merge and preserves strict conflict detection (HTTP 409).
 */
public final class DiagramThreeWayMerge {

    private DiagramThreeWayMerge() {}

    public record TouchedElements(
            Set<String> modifiedOrDeletedElementIds,
            Set<String> addedElementIds,
            Set<String> referencedNodeIdsOfAddedEdges) {

        public Set<String> lockProtectedElementIds() {
            Set<String> result = new LinkedHashSet<>(modifiedOrDeletedElementIds);
            result.addAll(referencedNodeIdsOfAddedEdges);
            result.addAll(addedElementIds);
            return Collections.unmodifiableSet(result);
        }
    }

    /**
     * Computes which elements were modified, deleted, added, or newly connected
     * between {@code base} and {@code target}.
     */
    public static TouchedElements diffElements(
            Map<String, Object> base,
            Map<String, Object> target) {
        Map<String, Map<String, Object>> baseNodes = indexElements(base, "nodes");
        Map<String, Map<String, Object>> targetNodes = indexElements(target, "nodes");
        Map<String, Map<String, Object>> baseEdges = indexElements(base, "edges");
        Map<String, Map<String, Object>> targetEdges = indexElements(target, "edges");

        if (baseNodes == null || targetNodes == null || baseEdges == null || targetEdges == null) {
            return new TouchedElements(Set.of(), Set.of(), Set.of());
        }

        Set<String> modifiedOrDeleted = new LinkedHashSet<>();
        Set<String> added = new LinkedHashSet<>();
        Set<String> addedEdgeEndpoints = new LinkedHashSet<>();

        Set<String> allNodeIds = new LinkedHashSet<>(baseNodes.keySet());
        allNodeIds.addAll(targetNodes.keySet());
        for (String nodeId : allNodeIds) {
            Map<String, Object> b = baseNodes.get(nodeId);
            Map<String, Object> t = targetNodes.get(nodeId);
            if (b == null && t != null) {
                added.add(nodeId);
            } else if (b != null && !canonicalEquals(b, t)) {
                modifiedOrDeleted.add(nodeId);
                addAssociationStructuralEdges(b, modifiedOrDeleted);
                if (t != null) {
                    addAssociationStructuralEdges(t, modifiedOrDeleted);
                }
            }
        }

        Set<String> allEdgeIds = new LinkedHashSet<>(baseEdges.keySet());
        allEdgeIds.addAll(targetEdges.keySet());
        for (String edgeId : allEdgeIds) {
            Map<String, Object> b = baseEdges.get(edgeId);
            Map<String, Object> t = targetEdges.get(edgeId);
            if (b == null && t != null) {
                added.add(edgeId);
                if (t.get("source") instanceof String source && baseNodes.containsKey(source)) {
                    addedEdgeEndpoints.add(source);
                }
                if (t.get("target") instanceof String targetNode && baseNodes.containsKey(targetNode)) {
                    addedEdgeEndpoints.add(targetNode);
                }
            } else if (b != null && !canonicalEquals(b, t)) {
                modifiedOrDeleted.add(edgeId);
            }
        }

        return new TouchedElements(
                Collections.unmodifiableSet(modifiedOrDeleted),
                Collections.unmodifiableSet(added),
                Collections.unmodifiableSet(addedEdgeEndpoints));
    }

    /**
     * Attempts a safe three-way merge of {@code incoming} (authored on {@code base})
     * onto {@code current}. Returns {@link Optional#empty()} if there is any element
     * collision or if the combined graph violates UML v1/v2 invariants.
     */
    public static Optional<Map<String, Object>> tryMerge(
            Map<String, Object> base,
            Map<String, Object> current,
            Map<String, Object> incoming) {
        if (base == null || current == null || incoming == null) {
            return Optional.empty();
        }

        Map<String, Map<String, Object>> baseNodes = indexElements(base, "nodes");
        Map<String, Map<String, Object>> currentNodes = indexElements(current, "nodes");
        Map<String, Map<String, Object>> incomingNodes = indexElements(incoming, "nodes");

        Map<String, Map<String, Object>> baseEdges = indexElements(base, "edges");
        Map<String, Map<String, Object>> currentEdges = indexElements(current, "edges");
        Map<String, Map<String, Object>> incomingEdges = indexElements(incoming, "edges");

        if (baseNodes == null || currentNodes == null || incomingNodes == null
                || baseEdges == null || currentEdges == null || incomingEdges == null) {
            return Optional.empty();
        }

        Optional<List<Map<String, Object>>> mergedNodesOpt =
                mergeCollection(baseNodes, currentNodes, incomingNodes);
        if (mergedNodesOpt.isEmpty()) {
            return Optional.empty();
        }

        Optional<List<Map<String, Object>>> mergedEdgesOpt =
                mergeCollection(baseEdges, currentEdges, incomingEdges);
        if (mergedEdgesOpt.isEmpty()) {
            return Optional.empty();
        }

        List<Map<String, Object>> mergedNodes = mergedNodesOpt.get();
        List<Map<String, Object>> mergedEdges = mergedEdgesOpt.get();

        // Remove dangling edges only when NEVER added/edited by the other party?
        // No: if User A deleted node X and User B kept an existing unmodified edge E to X,
        // wait: if User A deleted node X in `current`, User A ALSO deleted edge E in `current`
        // (`C_edge = null`, while `B_edge != null` and `I_edge == B_edge`), so `mergeCollection`
        // sees `changedInCurrent = true, changedInIncoming = false` -> keeps `C_edge` (null = deleted)!
        // Conversely, if User B modified or newly created an edge to X while User A deleted X,
        // `DiagramDocumentValidator.validate` below rejects the dangling reference as a true conflict!

        int mergedVersion = resolveVersion(current, incoming, mergedNodes, mergedEdges);

        Map<String, Object> merged = new LinkedHashMap<>();
        merged.put("version", mergedVersion);
        merged.put("nodes", deepCopyList(mergedNodes));
        merged.put("edges", deepCopyList(mergedEdges));

        if (merged.toString().getBytes(StandardCharsets.UTF_8).length > DiagramaService.TAMANO_MAXIMO_BYTES) {
            return Optional.empty();
        }

        try {
            DiagramDocumentValidator.validate(merged);
        } catch (DiagramValidationException | IllegalArgumentException invalidMerged) {
            return Optional.empty();
        }

        return Optional.of(merged);
    }

    private static Optional<List<Map<String, Object>>> mergeCollection(
            Map<String, Map<String, Object>> base,
            Map<String, Map<String, Object>> current,
            Map<String, Map<String, Object>> incoming) {
        Map<String, Map<String, Object>> resolved = new LinkedHashMap<>();
        Set<String> deletedIds = new LinkedHashSet<>();

        Set<String> orderedIds = new LinkedHashSet<>(current.keySet());
        orderedIds.addAll(incoming.keySet());
        orderedIds.addAll(base.keySet());

        for (String id : orderedIds) {
            Map<String, Object> b = base.get(id);
            Map<String, Object> c = current.get(id);
            Map<String, Object> i = incoming.get(id);

            boolean changedInCurrent = !canonicalEquals(b, c);
            boolean changedInIncoming = !canonicalEquals(b, i);

            Map<String, Object> winner;
            if (!changedInIncoming) {
                winner = c;
            } else if (!changedInCurrent) {
                winner = i;
            } else if (canonicalEquals(c, i)) {
                winner = c;
            } else {
                return Optional.empty();
            }

            if (winner == null) {
                deletedIds.add(id);
            } else {
                resolved.put(id, winner);
            }
        }

        List<Map<String, Object>> orderedResult = new ArrayList<>(resolved.size());
        for (String currentId : current.keySet()) {
            if (!deletedIds.contains(currentId) && resolved.containsKey(currentId)) {
                orderedResult.add(resolved.remove(currentId));
            }
        }
        for (String incomingId : incoming.keySet()) {
            if (!deletedIds.contains(incomingId) && resolved.containsKey(incomingId)) {
                orderedResult.add(resolved.remove(incomingId));
            }
        }
        orderedResult.addAll(resolved.values());
        return Optional.of(orderedResult);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, Object>> indexElements(Map<String, Object> document, String key) {
        if (document == null || !(document.get(key) instanceof List<?> list)) {
            return null;
        }
        Map<String, Map<String, Object>> indexed = new LinkedHashMap<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> rawMap)) {
                return null;
            }
            Object rawId = rawMap.get("id");
            if (!(rawId instanceof String id) || id.isBlank()) {
                // Legacy un-IDed edges cannot be merged element-by-element safely.
                return null;
            }
            if (indexed.containsKey(id)) {
                return null;
            }
            indexed.put(id, (Map<String, Object>) rawMap);
        }
        return indexed;
    }

    private static void addAssociationStructuralEdges(Map<String, Object> node, Set<String> target) {
        if (!(node.get("data") instanceof Map<?, ?> data)) return;
        if (!(data.get("association") instanceof Map<?, ?> association)) return;
        if (!(association.get("endpoints") instanceof List<?> endpoints)) return;
        for (Object rawEndpoint : endpoints) {
            if (rawEndpoint instanceof Map<?, ?> endpoint
                    && endpoint.get("relationshipId") instanceof String relId
                    && !relId.isBlank()) {
                target.add(relId);
            }
        }
    }

    private static int resolveVersion(
            Map<String, Object> current,
            Map<String, Object> incoming,
            List<Map<String, Object>> nodes,
            List<Map<String, Object>> edges) {
        if (usesUmlV2Features(nodes, edges)) {
            return 2;
        }
        int currentVersion = current.get("version") instanceof Number n ? n.intValue() : 1;
        int incomingVersion = incoming.get("version") instanceof Number n ? n.intValue() : 1;
        return Math.max(currentVersion, incomingVersion) >= 2 ? 2 : 1;
    }

    private static boolean usesUmlV2Features(
            List<Map<String, Object>> nodes,
            List<Map<String, Object>> edges) {
        for (Map<String, Object> node : nodes) {
            if (node.get("data") instanceof Map<?, ?> data) {
                Object kind = data.get("kind");
                if (kind instanceof String kindStr && !"ENTITY".equals(kindStr)) return true;
                if (data.get("methods") instanceof Collection<?> methods && !methods.isEmpty()) return true;
                if (Boolean.TRUE.equals(data.get("abstract"))) return true;
            }
        }
        for (Map<String, Object> edge : edges) {
            if (edge.get("data") instanceof Map<?, ?> data && data.get("relationKind") != null) {
                return true;
            }
        }
        return false;
    }

    public static boolean canonicalEquals(Object left, Object right) {
        if (left == right) return true;
        if (left == null || right == null) return false;
        return Objects.equals(canonicalize(left), canonicalize(right));
    }

    private static Object canonicalize(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            double d = number.doubleValue();
            if (!Double.isFinite(d)) return String.valueOf(d);
            if (d == 0.0d) return BigDecimal.ZERO;
            return BigDecimal.valueOf(d).stripTrailingZeros();
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new TreeMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() != null) {
                    sorted.put(String.valueOf(entry.getKey()), canonicalize(entry.getValue()));
                }
            }
            return sorted;
        }
        if (value instanceof List<?> list) {
            List<Object> normalized = new ArrayList<>(list.size());
            for (Object item : list) {
                normalized.add(canonicalize(item));
            }
            return normalized;
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> deepCopyMap(Map<String, Object> source) {
        if (source == null) return null;
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            copy.put(entry.getKey(), deepCopyValue(entry.getValue()));
        }
        return Collections.unmodifiableMap(copy);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> deepCopyList(List<Map<String, Object>> list) {
        List<Map<String, Object>> result = new ArrayList<>(list.size());
        for (Map<String, Object> item : list) {
            result.add((Map<String, Object>) deepCopyValue(item));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Object deepCopyValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                copy.put(String.valueOf(entry.getKey()), deepCopyValue(entry.getValue()));
            }
            return copy;
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            for (Object item : list) {
                copy.add(deepCopyValue(item));
            }
            return copy;
        }
        return value;
    }
}
