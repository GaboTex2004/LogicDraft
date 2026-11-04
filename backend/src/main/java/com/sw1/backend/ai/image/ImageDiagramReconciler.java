package com.sw1.backend.ai.image;

import com.sw1.backend.diagrama.validation.DiagramDocumentValidator;
import com.sw1.backend.diagrama.validation.DiagramValidationException;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.*;
import static com.sw1.backend.ai.image.ImageRecognitionValidator.objects;

/** Pure additive reconciliation. Never mutates its inputs or accepts model-generated IDs. */
public final class ImageDiagramReconciler {
    public record Result(Map<String, Object> document, List<String> matched, List<String> additions,
                         List<String> conflicts, List<String> warnings) {
        public boolean canApply() { return conflicts.isEmpty() && !additions.isEmpty(); }
    }
    private final Map<String, Object> document;
    private final List<Map<String, Object>> nodes;
    private final List<Map<String, Object>> edges;
    private final List<String> matched = new ArrayList<>(), additions = new ArrayList<>(),
            conflicts = new ArrayList<>(), warnings = new ArrayList<>();
    private final Set<String> ids = new HashSet<>();

    private ImageDiagramReconciler(Map<String, Object> base) {
        document = copy(base);
        nodes = objects(document.get("nodes"), 2000);
        edges = objects(document.get("edges"), 5000);
        collectIds(document);
    }

    public static Result reconcile(Map<String, Object> base, Map<String, Object> recognition) {
        ImageRecognitionValidator.validate(recognition);
        var planner = new ImageDiagramReconciler(base);
        for (Object warning : (List<?>) recognition.getOrDefault("warnings", List.of()))
            planner.warnings.add((String) warning);
        var operations = objects(recognition.get("operations"), 100);
        Map<String, Map<String, Object>> seen = new HashMap<>();
        List<Map<String, Object>> pendingNewAssocClasses = new ArrayList<>();
        for (var op : operations) {
            if (!"ADD_CLASSIFIER".equals(op.get("type"))) continue;
            String key = key(op.get("name"));
            var previous = seen.putIfAbsent(key, op);
            if (previous != null) {
                if (!previous.equals(op)) planner.conflicts.add("Identidad ambigua en la imagen: " + op.get("name"));
                continue;
            }
            if ("ASSOCIATION_CLASS".equals(op.get("kind")) && planner.namedNodes((String) op.get("name")).isEmpty()) {
                pendingNewAssocClasses.add(op);
            } else {
                planner.classifier(op);
            }
        }
        List<Map<String, Object>> relOps = operations.stream()
                .filter(op -> "ADD_RELATIONSHIP".equals(op.get("type")))
                .toList();
        planner.reconcileAssociationClassesAndRelationships(pendingNewAssocClasses, relOps);
        try {
            DiagramDocumentValidator.validate(planner.document);
            if (planner.document.toString().getBytes(StandardCharsets.UTF_8).length > 1_000_000)
                planner.conflicts.add("La propuesta supera el tamano maximo del documento.");
        } catch (DiagramValidationException invalid) {
            planner.conflicts.add(invalid.getMessage());
        }
        return new Result(planner.conflicts.isEmpty() ? planner.document : copy(base),
                List.copyOf(planner.matched), List.copyOf(planner.additions),
                List.copyOf(planner.conflicts), List.copyOf(planner.warnings));
    }

    private void reconcileAssociationClassesAndRelationships(
            List<Map<String, Object>> pendingNewAssocClasses,
            List<Map<String, Object>> relOps
    ) {
        Set<String> pendingAssocKeys = new HashSet<>();
        for (var op : pendingNewAssocClasses) pendingAssocKeys.add(key(op.get("name")));
        Set<Integer> consumedRelIndices = new HashSet<>();

        for (var assocOp : pendingNewAssocClasses) {
            String assocName = (String) assocOp.get("name");
            String assocKey = key(assocName);

            List<Integer> directConnectors = new ArrayList<>();
            for (int i = 0; i < relOps.size(); i++) {
                if (consumedRelIndices.contains(i)) continue;
                var rel = relOps.get(i);
                if (key(rel.get("source")).equals(assocKey) || key(rel.get("target")).equals(assocKey)) {
                    directConnectors.add(i);
                }
            }

            Map<String, String> connectorNeighbors = new LinkedHashMap<>();
            boolean invalidConnectorEndpoint = false;
            for (int idx : directConnectors) {
                var conn = relOps.get(idx);
                String s = (String) conn.get("source");
                String t = (String) conn.get("target");
                String sKey = key(s), tKey = key(t);
                if (sKey.equals(tKey)) {
                    invalidConnectorEndpoint = true;
                    break;
                }
                String otherName = sKey.equals(assocKey) ? t : s;
                String otherKey = key(otherName);
                if (pendingAssocKeys.contains(otherKey) || namedNodes(otherName).size() != 1) {
                    invalidConnectorEndpoint = true;
                    break;
                }
                connectorNeighbors.putIfAbsent(otherKey, otherName);
            }
            if (invalidConnectorEndpoint) {
                consumedRelIndices.addAll(directConnectors);
                String message = "Nueva clase asociativa " + assocName + ": requiere vincular su asociacion explicitamente; no se infiere por imagen.";
                warnings.add(message);
                conflicts.add(message);
                continue;
            }

            // Check if this box is merely the read-only derived join table preview of an existing N:M edge.
            var existingDerivedEdge = findExistingDerivedManyToManyEdge(assocOp, assocName, connectorNeighbors.keySet());
            if (existingDerivedEdge != null) {
                consumedRelIndices.addAll(directConnectors);
                matched.add("Tabla derivada " + assocName + " [" + existingDerivedEdge.get("id") + "]");
                continue;
            }

            List<Integer> candidateMainIndices = new ArrayList<>();
            for (int j = 0; j < relOps.size(); j++) {
                if (consumedRelIndices.contains(j) || directConnectors.contains(j)) continue;
                var rel = relOps.get(j);
                String sName = (String) rel.get("source"), tName = (String) rel.get("target");
                if (pendingAssocKeys.contains(key(sName)) || pendingAssocKeys.contains(key(tName))) continue;
                var sNodes = namedNodes(sName);
                var tNodes = namedNodes(tName);
                if (sNodes.size() != 1 || tNodes.size() != 1) continue;
                Object sId = sNodes.getFirst().get("id"), tId = tNodes.getFirst().get("id");
                if (Objects.equals(sId, tId)) continue;
                if (hasEdgeBetween(sId, tId) || findAssociationClassBetween(sId, tId) != null) continue;
                candidateMainIndices.add(j);
            }

            List<Integer> matchedMain = new ArrayList<>();
            for (int j : candidateMainIndices) {
                var rel = relOps.get(j);
                String sKey = key(rel.get("source")), tKey = key(rel.get("target"));
                boolean nameMatch = rel.get("name") != null && key(rel.get("name")).equals(assocKey);
                boolean compoundMatch = assocKey.equals(sKey + tKey) || assocKey.equals(tKey + sKey);
                boolean connectorMatch = !connectorNeighbors.isEmpty()
                        && Set.of(sKey, tKey).containsAll(connectorNeighbors.keySet());
                boolean connectorConsistent = connectorNeighbors.isEmpty()
                        || Set.of(sKey, tKey).containsAll(connectorNeighbors.keySet());
                if ((nameMatch || compoundMatch || connectorMatch) && connectorConsistent) {
                    matchedMain.add(j);
                }
            }
            if (matchedMain.isEmpty() && connectorNeighbors.isEmpty() && pendingNewAssocClasses.size() == 1) {
                for (int j : candidateMainIndices) {
                    if ("ASSOCIATION".equals(relOps.get(j).get("relationKind"))) {
                        matchedMain.add(j);
                    }
                }
            }

            if (matchedMain.size() == 1) {
                int mainIdx = matchedMain.getFirst();
                var mainRel = relOps.get(mainIdx);
                consumedRelIndices.add(mainIdx);
                consumedRelIndices.addAll(directConnectors);
                String sName = (String) mainRel.get("source"), tName = (String) mainRel.get("target");
                String sKey = key(sName), tKey = key(tName);
                String label = sName + " → " + tName;

                boolean kindValid = "ASSOCIATION".equals(mainRel.get("relationKind"))
                        && mainRel.get("aggregationEnd") == null;
                for (int connIdx : directConnectors) {
                    var conn = relOps.get(connIdx);
                    kindValid &= Set.of("ASSOCIATION", "DEPENDENCY").contains((String) conn.get("relationKind"))
                            && conn.get("aggregationEnd") == null;
                }
                if (!kindValid) {
                    conflicts.add("Tipo, extremo o multiplicidad incompatible: " + label);
                    continue;
                }

                String rawSource = (String) mainRel.get("sourceMultiplicity");
                String rawTarget = (String) mainRel.get("targetMultiplicity");
                for (int connIdx : directConnectors) {
                    var conn = relOps.get(connIdx);
                    String csKey = key(conn.get("source")), ctKey = key(conn.get("target"));
                    String csMult = (String) conn.get("sourceMultiplicity");
                    String ctMult = (String) conn.get("targetMultiplicity");
                    if (csKey.equals(sKey) || ctKey.equals(sKey)) {
                        String candidate = extractLegMultiplicity(conn, assocKey);
                        if (rawSource == null && candidate != null) rawSource = candidate;
                        String other = csKey.equals(sKey) ? ctMult : csMult;
                        if (rawTarget == null && other != null && csMult != null && ctMult != null) rawTarget = other;
                    }
                    if (csKey.equals(tKey) || ctKey.equals(tKey)) {
                        String candidate = extractLegMultiplicity(conn, assocKey);
                        if (rawTarget == null && candidate != null) rawTarget = candidate;
                        String other = csKey.equals(tKey) ? ctMult : csMult;
                        if (rawSource == null && other != null && csMult != null && ctMult != null) rawSource = other;
                    }
                }

                String sMult = canonicalMultiplicity(rawSource);
                String tMult = canonicalMultiplicity(rawTarget);
                String sCard = cardinality(sMult);
                String tCard = cardinality(tMult);
                if (sMult == null || tMult == null || sCard == null || tCard == null) {
                    conflicts.add("Multiplicidad ausente o no soportada: " + label);
                    continue;
                }
                createAssociationClass(assocOp, namedNodes(sName).getFirst(), namedNodes(tName).getFirst(),
                        sMult, tMult, sCard, tCard);
            } else if (matchedMain.isEmpty() && directConnectors.size() == 2 && connectorNeighbors.size() == 2) {
                consumedRelIndices.addAll(directConnectors);
                var leg1 = relOps.get(directConnectors.get(0));
                var leg2 = relOps.get(directConnectors.get(1));
                var neighborNames = new ArrayList<>(connectorNeighbors.values());
                String sName = neighborNames.get(0), tName = neighborNames.get(1);
                String label = sName + " → " + tName;
                var sNode = namedNodes(sName).getFirst();
                var tNode = namedNodes(tName).getFirst();
                if (hasEdgeBetween(sNode.get("id"), tNode.get("id"))
                        || findAssociationClassBetween(sNode.get("id"), tNode.get("id")) != null) {
                    String message = "Nueva clase asociativa " + assocName + ": requiere vincular su asociacion explicitamente; no se infiere por imagen.";
                    warnings.add(message);
                    conflicts.add(message);
                    continue;
                }
                boolean kindValid = Set.of("ASSOCIATION", "DEPENDENCY").contains((String) leg1.get("relationKind"))
                        && leg1.get("aggregationEnd") == null
                        && Set.of("ASSOCIATION", "DEPENDENCY").contains((String) leg2.get("relationKind"))
                        && leg2.get("aggregationEnd") == null;
                if (!kindValid) {
                    conflicts.add("Tipo, extremo o multiplicidad incompatible: " + label);
                    continue;
                }
                String sMult = canonicalMultiplicity(extractLegMultiplicity(leg1, assocKey));
                String tMult = canonicalMultiplicity(extractLegMultiplicity(leg2, assocKey));
                String sCard = cardinality(sMult);
                String tCard = cardinality(tMult);
                if (sMult == null || tMult == null || sCard == null || tCard == null) {
                    conflicts.add("Multiplicidad ausente o no soportada: " + label);
                    continue;
                }
                createAssociationClass(assocOp, sNode, tNode, sMult, tMult, sCard, tCard);
            } else {
                consumedRelIndices.addAll(directConnectors);
                String message = "Nueva clase asociativa " + assocName + ": requiere vincular su asociacion explicitamente; no se infiere por imagen.";
                warnings.add(message);
                conflicts.add(message);
            }
        }

        for (int i = 0; i < relOps.size(); i++) {
            if (!consumedRelIndices.contains(i)) {
                relationship(relOps.get(i));
            }
        }
    }

    private static String extractLegMultiplicity(Map<String, Object> leg, String assocKey) {
        String m1 = canonicalMultiplicity((String) leg.get("sourceMultiplicity"));
        String m2 = canonicalMultiplicity((String) leg.get("targetMultiplicity"));
        if (m1 == null && m2 == null) return null;
        if (m1 != null && m2 == null) return m1;
        if (m2 != null && m1 == null) return m2;
        if ("1".equals(m1) && !"1".equals(m2)) return m2;
        if ("1".equals(m2) && !"1".equals(m1)) return m1;
        return key(leg.get("source")).equals(assocKey) ? m2 : m1;
    }

    private Map<String, Object> findExistingDerivedManyToManyEdge(
            Map<String, Object> assocOp,
            String assocName,
            Set<String> connectorNeighborKeys
    ) {
        var methods = objects(assocOp.get("methods"), 100);
        List<?> literals = (List<?>) assocOp.get("literals");
        if (!methods.isEmpty() || (literals != null && !literals.isEmpty())) return null;
        String candidateTable = key(sqlName(technicalName(assocName)));
        for (var edge : edges) {
            if (findAssociationClassForStructuralEdge(edge.get("id")) != null) continue;
            var edgeData = data(edge);
            String sc = cardinality(multiplicity(edgeData, "source"));
            String tc = cardinality(multiplicity(edgeData, "target"));
            if (!isManyCardinality(sc) || !isManyCardinality(tc)) continue;
            var sNode = nodeById(edge.get("source"));
            var tNode = nodeById(edge.get("target"));
            if (sNode == null || tNode == null) continue;
            String sName = (String) data(sNode).get("name");
            String tName = (String) data(tNode).get("name");
            if (!connectorNeighborKeys.isEmpty()
                    && !Set.of(key(sName), key(tName)).containsAll(connectorNeighborKeys)) {
                continue;
            }
            String customTable = edgeData.get("joinTableName") instanceof String jt && !jt.isBlank()
                    ? jt.strip() : null;
            String relName = edgeData.get("name") instanceof String rn && !rn.isBlank()
                    ? technicalName(rn) : "";
            String derivedForward = sqlName(customTable != null ? technicalName(customTable)
                    : technicalName(sName) + technicalName(tName) + relName);
            String derivedReverse = sqlName(customTable != null ? technicalName(customTable)
                    : technicalName(tName) + technicalName(sName) + relName);
            if (!candidateTable.equals(key(derivedForward)) && !candidateTable.equals(key(derivedReverse))) {
                continue;
            }
            String fkSource = key(sqlName(technicalName(sName) + "Id"));
            String fkTarget = key(sqlName(technicalName(tName) + "Id"));
            var attrs = objects(assocOp.get("attributes"), 100);
            boolean onlyFks = attrs.stream().allMatch(attr -> {
                var cleaned = copy(attr);
                if (!normalizeObservedMember(cleaned, false)) return true;
                String attrSql = key(sqlName(technicalName((String) cleaned.get("name"))));
                return "id".equals(attrSql) || attrSql.equals(fkSource) || attrSql.equals(fkTarget);
            });
            if (onlyFks) return edge;
        }
        return null;
    }

    private void createAssociationClass(
            Map<String, Object> observed,
            Map<String, Object> sourceNode,
            Map<String, Object> targetNode,
            String sMult,
            String tMult,
            String sCard,
            String tCard
    ) {
        String name = (String) observed.get("name");
        String sourceId = (String) sourceNode.get("id");
        String targetId = (String) targetNode.get("id");
        String sourceName = (String) data(sourceNode).get("name");
        String targetName = (String) data(targetNode).get("name");
        String tableName = sqlName(technicalName(name));
        String fkSource = sqlName(technicalName(sourceName) + "Id");
        String fkTarget = sqlName(technicalName(targetName) + "Id");
        if (tableName.isEmpty() || tableName.length() > 55 || !tableName.matches("[a-z][a-z0-9_]*")
                || fkSource.isEmpty() || fkTarget.isEmpty() || fkSource.equals(fkTarget)) {
            conflicts.add("Metadatos de clase asociativa invalidos: " + name);
            return;
        }
        if (isPhysicalTableInUse(tableName)) {
            conflicts.add("La tabla fisica '" + tableName + "' ya esta en uso.");
            return;
        }

        String assocId = id();
        String sourceEdgeId = id();
        String targetEdgeId = id();

        Map<String, Object> epSource = new LinkedHashMap<>();
        epSource.put("role", "SOURCE");
        epSource.put("entityId", sourceId);
        epSource.put("relationshipId", sourceEdgeId);
        epSource.put("foreignKeyName", fkSource);

        Map<String, Object> epTarget = new LinkedHashMap<>();
        epTarget.put("role", "TARGET");
        epTarget.put("entityId", targetId);
        epTarget.put("relationshipId", targetEdgeId);
        epTarget.put("foreignKeyName", fkTarget);

        Map<String, Object> association = new LinkedHashMap<>();
        association.put("kind", "MANY_TO_MANY_ASSOCIATION");
        association.put("tableName", tableName);
        association.put("uniquePair", true);
        association.put("sourceMultiplicity", sCard);
        association.put("targetMultiplicity", tCard);
        association.put("endpoints", List.of(epSource, epTarget));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", assocId);
        data.put("name", name);
        data.put("attributes", new ArrayList<>());
        data.put("kind", "ASSOCIATION_CLASS");
        data.put("methods", new ArrayList<>());
        if (observed.get("abstract") != null) data.put("abstract", observed.get("abstract"));
        data.put("association", association);

        double sx = coord(sourceNode, "x", 180), sy = coord(sourceNode, "y", 180);
        double tx = coord(targetNode, "x", 480), ty = coord(targetNode, "y", 180);
        Map<String, Object> position = new LinkedHashMap<>();
        position.put("x", (sx + tx) / 2.0);
        position.put("y", Math.max(sy, ty) + 160.0);

        nodes.add(new LinkedHashMap<>(Map.of(
                "id", assocId,
                "type", "entity",
                "position", position,
                "data", data
        )));
        additions.add("Clasificador ASSOCIATION_CLASS: " + name);

        mergeMembers(data, objects(observed.get("attributes"), 100), "attributes", name);
        ensureAssociationPrimaryKey(data, name);
        var methods = objects(observed.get("methods"), 100);
        mergeMembers(data, methods, "methods", name);
        List<?> incomingLiterals = (List<?>) observed.get("literals");
        if (incomingLiterals != null && !incomingLiterals.isEmpty()) {
            conflicts.add("Literales en clasificador no enumeracion: " + name);
            return;
        }

        Map<String, Object> sEdgeData = new LinkedHashMap<>();
        sEdgeData.put("relationKind", "ASSOCIATION");
        sEdgeData.put("sourceMultiplicity", "1");
        sEdgeData.put("targetMultiplicity", tMult);
        sEdgeData.put("sourceCardinality", "ONE_ONE");
        sEdgeData.put("targetCardinality", tCard);
        edges.add(new LinkedHashMap<>(Map.of(
                "id", sourceEdgeId,
                "source", sourceId,
                "target", assocId,
                "type", "relationship",
                "data", sEdgeData
        )));

        Map<String, Object> tEdgeData = new LinkedHashMap<>();
        tEdgeData.put("relationKind", "ASSOCIATION");
        tEdgeData.put("sourceMultiplicity", "1");
        tEdgeData.put("targetMultiplicity", sMult);
        tEdgeData.put("sourceCardinality", "ONE_ONE");
        tEdgeData.put("targetCardinality", sCard);
        edges.add(new LinkedHashMap<>(Map.of(
                "id", targetEdgeId,
                "source", targetId,
                "target", assocId,
                "type", "relationship",
                "data", tEdgeData
        )));

        document.put("version", 2);
        additions.add("Relacion ASSOCIATION: " + sourceName + " → " + targetName);
    }

    private void ensureAssociationPrimaryKey(Map<String, Object> data, String name) {
        var current = objects(data.computeIfAbsent("attributes", ignored -> new ArrayList<>()), 1000);
        var pks = current.stream().filter(attr -> Boolean.TRUE.equals(attr.get("primaryKey"))).toList();
        if (pks.isEmpty()) {
            var idAttr = current.stream()
                    .filter(attr -> "id".equals(key(attr.get("name")))
                            && Set.of("INTEGER", "BIGINT").contains(attr.get("type")))
                    .findFirst()
                    .orElse(null);
            if (idAttr != null) {
                idAttr.put("primaryKey", true);
                idAttr.put("nullable", false);
                return;
            }
            if (current.stream().noneMatch(attr -> "id".equals(key(attr.get("name"))))) {
                Map<String, Object> pk = new LinkedHashMap<>();
                pk.put("id", id());
                pk.put("name", "id");
                pk.put("type", "INTEGER");
                pk.put("primaryKey", true);
                pk.put("nullable", false);
                current.addFirst(pk);
                return;
            }
        }
        var updatedPks = current.stream().filter(attr -> Boolean.TRUE.equals(attr.get("primaryKey"))).toList();
        if (updatedPks.size() != 1
                || !Set.of("INTEGER", "BIGINT").contains(updatedPks.getFirst().get("type"))
                || Boolean.TRUE.equals(updatedPks.getFirst().get("nullable"))) {
            conflicts.add("La entidad asociativa " + name + " requiere una PK INTEGER o BIGINT generada.");
        }
    }

    private boolean isPhysicalTableInUse(String tableName) {
        String targetKey = key(tableName);
        for (var node : nodes) {
            var d = data(node);
            var assoc = mapOrEmpty(d.get("association"));
            String nodeTable = assoc.get("tableName") instanceof String tn && !tn.isBlank()
                    ? tn : sqlName(technicalName((String) d.get("name")));
            if (targetKey.equals(key(nodeTable))) return true;
        }
        return false;
    }

    private void classifier(Map<String, Object> observed) {
        String name = (String) observed.get("name");
        List<Map<String, Object>> candidates = namedNodes(name);
        if (candidates.size() > 1) { conflicts.add("Nombre ambiguo: " + name); return; }
        Map<String, Object> data;
        String kind = (String) observed.get("kind");
        if (candidates.isEmpty()) {
            if (kind == null) { conflicts.add("Tipo UML incierto: " + name); return; }
            if ("ASSOCIATION_CLASS".equals(kind)) {
                String message = "Nueva clase asociativa " + name + ": requiere vincular su asociacion explicitamente; no se infiere por imagen.";
                warnings.add(message); conflicts.add(message); return;
            }
            data = new LinkedHashMap<>();
            String id = id();
            data.put("id", id); data.put("name", name); data.put("attributes", new ArrayList<>());
            data.put("kind", kind);
            if (observed.get("abstract") != null) data.put("abstract", observed.get("abstract"));
            if (!"ENTITY".equals(kind)) document.put("version", 2);
            double bottom = nodes.stream().mapToDouble(node -> {
                var position = mapOrEmpty(node.get("position"));
                var d = data(node);
                double y = position.get("y") instanceof Number n ? n.doubleValue() : 0;
                double height = d.get("height") instanceof Number n ? n.doubleValue()
                        : 100 + 32 * (((List<?>) d.getOrDefault("attributes", List.of())).size()
                        + ((List<?>) d.getOrDefault("methods", List.of())).size()
                        + ((List<?>) d.getOrDefault("literals", List.of())).size());
                return y + Math.max(100, height) + 80;
            }).max().orElse(180);
            nodes.add(new LinkedHashMap<>(Map.of("id", id, "type", "entity", "position",
                    new LinkedHashMap<>(Map.of("x", 180, "y", bottom)), "data", data)));
            additions.add("Clasificador " + kind + ": " + name);
        } else {
            data = data(candidates.getFirst());
            String existingKind = (String) data.getOrDefault("kind", "ENTITY");
            if (kind != null && !kind.equals(existingKind)) {
                conflicts.add("Tipo de " + name + ": existente " + existingKind + ", imagen " + kind); return;
            }
            kind = existingKind;
            if (observed.get("abstract") != null && !observed.get("abstract").equals(data.getOrDefault("abstract", false)))
                conflicts.add("Modificador abstract incompatible: " + name);
            matched.add(name + " [" + candidates.getFirst().get("id") + "]");
        }
        mergeMembers(data, objects(observed.get("attributes"), 100), "attributes", name);
        var methods = objects(observed.get("methods"), 100);
        if (!methods.isEmpty() && "ENTITY".equals(kind)) {
            conflicts.add("Metodos en ENTITY " + name + ": requiere una decision UML explicita.");
        } else {
            mergeMembers(data, methods, "methods", name);
            if (!methods.isEmpty()) document.put("version", 2);
        }
        List<?> incomingLiterals = (List<?>) observed.get("literals");
        if (!incomingLiterals.isEmpty()) {
            if (!"ENUMERATION".equals(kind)) { conflicts.add("Literales en clasificador no enumeracion: " + name); return; }
            @SuppressWarnings("unchecked")
            List<String> literals = (List<String>) data.computeIfAbsent("literals", ignored -> new ArrayList<>());
            for (Object rawLiteral : incomingLiterals) {
                String cleanLiteral = sanitizeLiteral(rawLiteral == null ? null : rawLiteral.toString());
                if (cleanLiteral == null) continue;
                if (literals.stream().noneMatch(item -> key(item).equals(key(cleanLiteral)))) {
                    literals.add(cleanLiteral); additions.add("Literal " + name + "." + cleanLiteral);
                } else {
                    matched.add("Literal " + name + "." + cleanLiteral);
                }
            }
        }
    }

    private void mergeMembers(Map<String, Object> data, List<Map<String, Object>> incoming, String field, String owner) {
        if (incoming.isEmpty()) return; // Do not even add absent empty fields to existing nodes.
        boolean hadField = data.get(field) instanceof List<?>;
        var current = hadField ? objects(data.get(field), 1000) : new ArrayList<Map<String, Object>>();
        boolean method = field.equals("methods");
        for (var raw : incoming) {
            var item = copy(raw);
            item.values().removeIf(Objects::isNull);
            if (!normalizeObservedMember(item, method)) continue;
            String label = owner + "." + item.get("name");
            if (!method) {
                if (isStructuralForeignKey(data, (String) item.get("name"))) {
                    matched.add("FK estructural " + label);
                    continue;
                }
                String type = sqlType((String) item.get("type"));
                if (type == null) {
                    warnings.add("Tipo de atributo no soportado: " + label + " (" + item.get("type") + ")");
                    conflicts.add("No se puede representar el tipo de " + label + " sin degradarlo"); continue;
                }
                item.put("type", type);
            }
            var matches = current.stream().filter(member -> key(member.get("name")).equals(key(item.get("name")))).toList();
            if (matches.size() > 1) { conflicts.add("Miembro ambiguo o sobrecargado: " + label); continue; }
            if (!matches.isEmpty()) {
                if (!compatibleMember(matches.getFirst(), item, method)) conflicts.add("Definicion incompatible: " + label);
                else matched.add("Miembro " + label + " [" + matches.getFirst().get("id") + "]");
                continue;
            }
            item.put("id", id());
            if (method) {
                item.putIfAbsent("visibility", "public");
                for (var parameter : objects(item.get("parameters"), 30)) parameter.put("id", id());
            } else {
                item.putIfAbsent("primaryKey", false);
                if (Boolean.TRUE.equals(item.get("primaryKey"))) item.put("nullable", false);
                else item.putIfAbsent("nullable", true);
            }
            if (!hadField) {
                data.put(field, current);
                hadField = true;
            }
            current.add(item);
            additions.add((method ? "Metodo " : "Atributo ") + label);
        }
    }

    private static String sanitizeLiteral(String raw) {
        if (raw == null) return null;
        String value = raw.strip().replaceAll("^[·•+~#-]+\\s*", "").strip();
        String k = key(value);
        if (value.isEmpty() || Set.of("sinatributos", "sinmetodos", "sinliterales", "vistaderivada").contains(k)) {
            return null;
        }
        return value;
    }

    private static boolean normalizeObservedMember(Map<String, Object> item, boolean method) {
        if (!(item.get("name") instanceof String rawName)) return false;
        String name = rawName.strip();
        if (name.toLowerCase(Locale.ROOT).endsWith("{static}")) {
            name = name.substring(0, name.length() - "{static}".length()).strip();
            item.putIfAbsent("static", true);
        }
        if (method && name.toLowerCase(Locale.ROOT).endsWith("{abstract}")) {
            name = name.substring(0, name.length() - "{abstract}".length()).strip();
            item.putIfAbsent("abstract", true);
        }
        boolean changed = true;
        while (changed && !name.isEmpty()) {
            changed = false;
            if (name.startsWith("·") || name.startsWith("•")) {
                name = name.substring(1).strip();
                changed = true;
            } else if (name.length() > 2 && "+-#~".indexOf(name.charAt(0)) >= 0 && Character.isWhitespace(name.charAt(1))) {
                String vis = switch (name.charAt(0)) {
                    case '+' -> "public";
                    case '-' -> "private";
                    case '#' -> "protected";
                    case '~' -> "package";
                    default -> null;
                };
                if (vis != null) item.putIfAbsent("visibility", vis);
                name = name.substring(1).strip();
                changed = true;
            } else if (!method && name.matches("(?i)^(?:\\[\\s*PK\\s*\\]|<<\\s*PK\\s*>>|PK\\s*[:\\-]?)\\s+.+")) {
                name = name.replaceFirst("(?i)^(?:\\[\\s*PK\\s*\\]|<<\\s*PK\\s*>>|PK\\s*[:\\-]?)\\s+", "").strip();
                item.put("primaryKey", true);
                item.put("nullable", false);
                changed = true;
            } else if (!method && name.matches("(?i)^(?:\\[\\s*FK\\s*\\]|<<\\s*FK\\s*>>|FK\\s*[:\\-]?)\\s+.+")) {
                name = name.replaceFirst("(?i)^(?:\\[\\s*FK\\s*\\]|<<\\s*FK\\s*>>|FK\\s*[:\\-]?)\\s+", "").strip();
                changed = true;
            }
        }
        if (method && name.endsWith("()")) {
            name = name.substring(0, name.length() - 2).strip();
        }
        if (!method && name.contains(":")) {
            String[] parts = name.split(":", 2);
            if (!parts[0].strip().isEmpty()) {
                name = parts[0].strip();
            }
        }
        String k = key(name);
        if (name.isEmpty() || Set.of("sinatributos", "sinmetodos", "sinliterales", "vistaderivada").contains(k)) {
            return false;
        }
        item.put("name", name);
        return true;
    }

    private static boolean isStructuralForeignKey(Map<String, Object> classifierData, String attributeName) {
        if (attributeName == null || !(classifierData.get("association") instanceof Map<?, ?> assoc)) return false;
        if (!(assoc.get("endpoints") instanceof List<?> endpoints)) return false;
        String candidate = key(sqlName(technicalName(attributeName)));
        if (candidate.isEmpty()) return false;
        var currentAttrs = objects(classifierData.getOrDefault("attributes", List.of()), 1000);
        boolean alreadyStoredAsOwnAttr = currentAttrs.stream()
                .anyMatch(attr -> key(attr.get("name")).equals(key(attributeName)));
        if (alreadyStoredAsOwnAttr) return false;
        for (Object rawEp : endpoints) {
            var ep = mapOrEmpty(rawEp);
            if (ep.get("foreignKeyName") instanceof String fk
                    && candidate.equals(key(sqlName(technicalName(fk))))) {
                return true;
            }
        }
        return false;
    }

    private static boolean compatibleMember(Map<String, Object> existing, Map<String, Object> incoming, boolean method) {
        for (var entry : incoming.entrySet()) {
            String field = entry.getKey();
            if (field.equals("name")) continue;
            Object expected = existing.get(field);
            if (field.equals("parameters")) {
                var left = objects(expected, 1000); var right = objects(entry.getValue(), 30);
                if (left.size() != right.size()) return false;
                for (int i = 0; i < left.size(); i++) {
                    if (!key(left.get(i).get("name")).equals(key(right.get(i).get("name")))
                            || !sameType(left.get(i).get("type"), right.get(i).get("type"))) return false;
                }
                continue;
            }
            if (!method && field.equals("nullable")) continue;
            if (!method && field.equals("visibility") && Boolean.TRUE.equals(existing.get("primaryKey"))) continue;
            if (!method && field.equals("primaryKey")
                    && Boolean.TRUE.equals(existing.get("primaryKey"))
                    && Boolean.FALSE.equals(entry.getValue())) continue;
            if (expected == null) {
                if (!method && field.equals("visibility")) continue;
                expected = switch (field) {
                    case "nullable" -> !Boolean.TRUE.equals(existing.get("primaryKey"));
                    case "primaryKey", "static", "abstract" -> false;
                    case "visibility" -> "public";
                    default -> null;
                };
            }
            if (!method && field.equals("type")) expected = sqlType((String) expected);
            if (method && field.equals("returnType")) {
                if (!sameType(expected, entry.getValue())) return false;
                continue;
            }
            if (!Objects.equals(expected, entry.getValue())) return false;
        }
        return true;
    }

    private static boolean sameType(Object left, Object right) {
        if (Objects.equals(left, right)) return true;
        if (!(left instanceof String l) || !(right instanceof String r)) return false;
        String sqlLeft = sqlType(l);
        String sqlRight = sqlType(r);
        if (sqlLeft != null && sqlLeft.equals(sqlRight)) return true;
        return key(l).equals(key(r));
    }

    private void relationship(Map<String, Object> observed) {
        String label = observed.get("source") + " → " + observed.get("target");
        var sources = namedNodes((String) observed.get("source"));
        var targets = namedNodes((String) observed.get("target"));
        if (sources.size() != 1 || targets.size() != 1) { conflicts.add("Extremos ausentes o ambiguos: " + label); return; }
        Object source = sources.getFirst().get("id"), target = targets.getFirst().get("id");
        String kind = (String) observed.get("relationKind");
        boolean symmetric = Set.of("ASSOCIATION", "AGGREGATION", "COMPOSITION").contains(kind);
        var samePair = edges.stream().filter(edge ->
                Objects.equals(source, edge.get("source")) && Objects.equals(target, edge.get("target"))
                || Objects.equals(source, edge.get("target")) && Objects.equals(target, edge.get("source"))).toList();
        if (!samePair.isEmpty()) {
            var named = samePair.stream().filter(edge -> {
                if (key(data(edge).get("name")).equals(key(observed.get("name")))) return true;
                var owner = findAssociationClassForStructuralEdge(edge.get("id"));
                if (owner != null && observed.get("name") != null) {
                    var ownerData = data(owner);
                    var ownerAssoc = mapOrEmpty(ownerData.get("association"));
                    return key(observed.get("name")).equals(key(ownerData.get("name")))
                            || key(observed.get("name")).equals(key(ownerAssoc.get("tableName")));
                }
                return false;
            }).toList();
            if (named.size() != 1) { conflicts.add("Nombre o identidad de relacion ambiguos: " + label); return; }
            var edge = named.getFirst(); var old = data(edge);
            boolean reverse = !source.equals(edge.get("source"));
            var ownerAssoc = findAssociationClassForStructuralEdge(edge.get("id"));
            boolean compatible = ownerAssoc != null
                    ? Set.of("ASSOCIATION", "DEPENDENCY").contains(kind) && observed.get("aggregationEnd") == null
                    : (!reverse || symmetric) && kind.equals(old.getOrDefault("relationKind", "ASSOCIATION"));
            for (String end : List.of("source", "target")) {
                Object value = observed.get(end + "Multiplicity");
                if (value == null) continue;
                String originalEnd = reverse ? (end.equals("source") ? "target" : "source") : end;
                String observedMult = canonicalMultiplicity(value.toString());
                boolean endMatch = observedMult.equals(canonicalMultiplicity(multiplicity(old, originalEnd)));
                if (!endMatch && ownerAssoc != null) {
                    var assoc = mapOrEmpty(data(ownerAssoc).get("association"));
                    endMatch = observedMult.equals(canonicalMultiplicity(associationMultiplicity(assoc, "source")))
                            || observedMult.equals(canonicalMultiplicity(associationMultiplicity(assoc, "target")));
                }
                compatible &= endMatch;
            }
            if (observed.get("aggregationEnd") != null) {
                String end = (String) old.getOrDefault("aggregationEnd", "SOURCE");
                if (reverse) end = end.equals("SOURCE") ? "TARGET" : "SOURCE";
                compatible &= observed.get("aggregationEnd").equals(end);
            }
            if (!compatible) conflicts.add("Tipo, extremo o multiplicidad incompatible: " + label);
            else matched.add("Relacion " + label + " [" + edge.get("id") + "]");
            return;
        }
        var assocNode = findAssociationClassBetween(source, target);
        if (assocNode != null) {
            var assocData = data(assocNode);
            var assoc = mapOrEmpty(assocData.get("association"));
            Object assocSource = associationEndpointEntityId(assoc, "SOURCE");
            boolean reverse = !Objects.equals(source, assocSource);
            Object obsName = observed.get("name");
            boolean nameCompatible = obsName == null
                    || key(obsName).equals(key(assocData.get("name")))
                    || key(obsName).equals(key(assoc.get("tableName")));
            boolean compatible = "ASSOCIATION".equals(kind)
                    && observed.get("aggregationEnd") == null
                    && nameCompatible;
            for (String end : List.of("source", "target")) {
                Object value = observed.get(end + "Multiplicity");
                if (value == null) continue;
                String originalEnd = reverse ? (end.equals("source") ? "target" : "source") : end;
                String expectedMult = associationMultiplicity(assoc, originalEnd);
                compatible &= canonicalMultiplicity(value.toString()).equals(canonicalMultiplicity(expectedMult));
            }
            if (!compatible) conflicts.add("Tipo, extremo o multiplicidad incompatible: " + label);
            else matched.add("Relacion " + label + " [" + assocNode.get("id") + "]");
            return;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("relationKind", kind);
        if (observed.get("name") != null) data.put("name", observed.get("name"));
        if (symmetric) {
            for (String end : List.of("source", "target")) {
                String rawValue = (String) observed.get(end + "Multiplicity");
                String value = rawValue == null ? null : canonicalMultiplicity(rawValue);
                if (value == null || !value.matches("(?:\\d+|\\*|\\d+\\.\\.(?:\\d+|\\*))")) {
                    conflicts.add("Multiplicidad ausente o no soportada: " + label); return;
                }
                if (value.contains("..") && !value.endsWith("*")) {
                    String[] bounds = value.split("\\.\\.");
                    try {
                        if (Long.parseLong(bounds[0]) > Long.parseLong(bounds[1])) throw new NumberFormatException();
                    } catch (NumberFormatException invalid) { conflicts.add("Intervalo invalido: " + label); return; }
                }
                data.put(end + "Multiplicity", value);
            }
            String sc = cardinality((String) data.get("sourceMultiplicity"));
            String tc = cardinality((String) data.get("targetMultiplicity"));
            if (sc != null && tc != null) { data.put("sourceCardinality", sc); data.put("targetCardinality", tc); }
        } else if (observed.get("sourceMultiplicity") != null || observed.get("targetMultiplicity") != null) {
            conflicts.add("Multiplicidad en relacion no multiplicativa: " + label); return;
        }
        if (Set.of("AGGREGATION", "COMPOSITION").contains(kind)) {
            if (observed.get("aggregationEnd") == null) { conflicts.add("Extremo del diamante incierto: " + label); return; }
            data.put("aggregationEnd", observed.get("aggregationEnd"));
        } else if (observed.get("aggregationEnd") != null) { conflicts.add("Diamante incompatible: " + label); return; }
        edges.add(new LinkedHashMap<>(Map.of("id", id(), "source", source, "target", target,
                "type", "relationship", "data", data)));
        document.put("version", 2);
        additions.add("Relacion " + kind + ": " + label);
    }

    private boolean hasEdgeBetween(Object source, Object target) {
        return edges.stream().anyMatch(edge ->
                Objects.equals(source, edge.get("source")) && Objects.equals(target, edge.get("target"))
                || Objects.equals(source, edge.get("target")) && Objects.equals(target, edge.get("source")));
    }

    private Map<String, Object> nodeById(Object id) {
        return nodes.stream().filter(node -> Objects.equals(id, node.get("id"))).findFirst().orElse(null);
    }

    private static boolean isManyCardinality(String cardinality) {
        return "ZERO_MANY".equals(cardinality) || "ONE_MANY".equals(cardinality);
    }

    private static double coord(Map<String, Object> node, String axis, double fallback) {
        var pos = mapOrEmpty(node.get("position"));
        return pos.get(axis) instanceof Number n ? n.doubleValue() : fallback;
    }

    private static String multiplicity(Map<String, Object> data, String end) {
        if (data.get(end + "Multiplicity") instanceof String value) return value;
        String cardinality = (String) data.get(end + "Cardinality");
        if (cardinality == null) {
            String legacy = (String) data.getOrDefault("relationshipType", "ONE_TO_ONE");
            boolean many = end.equals("source") ? legacy.startsWith("MANY") : legacy.endsWith("MANY");
            return many ? "0..*" : "1";
        }
        return switch (cardinality) {
            case "ONE_ONE" -> "1"; case "ZERO_ONE" -> "0..1";
            case "ONE_MANY" -> "1..*"; case "ZERO_MANY" -> "0..*"; default -> "?";
        };
    }
    private static String cardinality(String value) {
        String canonical = canonicalMultiplicity(value);
        if (canonical == null) return null;
        return switch (canonical) {
            case "1", "1..1" -> "ONE_ONE"; case "0..1" -> "ZERO_ONE";
            case "*", "0..*" -> "ZERO_MANY"; case "1..*" -> "ONE_MANY"; default -> null;
        };
    }
    private static String canonicalMultiplicity(String raw) {
        if (raw == null) return null;
        String value = raw.strip().replaceAll("\\s+", "");
        return switch (value.toUpperCase(Locale.ROOT)) {
            case "1", "1..1", "ONE_ONE" -> "1";
            case "0..1", "ZERO_ONE" -> "0..1";
            case "*", "N", "M", "0..*", "0..N", "0..M", "*..*", "ZERO_MANY", "MANY" -> "0..*";
            case "1..*", "1..N", "1..M", "ONE_MANY" -> "1..*";
            default -> value;
        };
    }
    private Map<String, Object> findAssociationClassBetween(Object source, Object target) {
        for (var node : nodes) {
            var assoc = mapOrEmpty(data(node).get("association"));
            if (assoc.isEmpty()) continue;
            Object s = associationEndpointEntityId(assoc, "SOURCE");
            Object t = associationEndpointEntityId(assoc, "TARGET");
            if (Objects.equals(source, s) && Objects.equals(target, t)
                    || Objects.equals(source, t) && Objects.equals(target, s)) {
                return node;
            }
        }
        return null;
    }
    private Map<String, Object> findAssociationClassForStructuralEdge(Object edgeId) {
        if (edgeId == null) return null;
        for (var node : nodes) {
            var assoc = mapOrEmpty(data(node).get("association"));
            if (assoc.isEmpty()) continue;
            if (Objects.equals(edgeId, associationEndpointRelationshipId(assoc, "SOURCE"))
                    || Objects.equals(edgeId, associationEndpointRelationshipId(assoc, "TARGET"))) {
                return node;
            }
        }
        return null;
    }
    private static Object associationEndpointEntityId(Map<String, Object> assoc, String role) {
        if (!(assoc.get("endpoints") instanceof List<?> endpoints)) return null;
        for (Object item : endpoints) {
            var ep = mapOrEmpty(item);
            if (role.equals(ep.get("role"))) return ep.get("entityId");
        }
        return null;
    }
    private static Object associationEndpointRelationshipId(Map<String, Object> assoc, String role) {
        if (!(assoc.get("endpoints") instanceof List<?> endpoints)) return null;
        for (Object item : endpoints) {
            var ep = mapOrEmpty(item);
            if (role.equals(ep.get("role"))) return ep.get("relationshipId");
        }
        return null;
    }
    private String associationMultiplicity(Map<String, Object> assoc, String end) {
        if (assoc.get(end + "Multiplicity") instanceof String value && !value.isBlank()) return value;
        String oppositeRole = "source".equals(end) ? "TARGET" : "SOURCE";
        Object relId = associationEndpointRelationshipId(assoc, oppositeRole);
        if (relId != null) {
            for (var edge : edges) {
                if (Objects.equals(relId, edge.get("id"))) return multiplicity(data(edge), "target");
            }
        }
        return "0..*";
    }
    private List<Map<String, Object>> namedNodes(String name) {
        return nodes.stream().filter(node -> key(data(node).get("name")).equals(key(name))).toList();
    }
    private static String key(Object name) {
        return name == null ? "" : Normalizer.normalize(name.toString().strip(), Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT).replaceAll("[\\s_-]+", "");
    }
    private static String technicalName(String value) {
        if (value == null) return "";
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
        return value == null ? "" : value.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
    }
    private static String sqlType(String value) {
        if (value == null) return null;
        String cleaned = value.strip().toUpperCase(Locale.ROOT).replaceAll("\\s*\\([^)]*\\)\\s*$", "");
        return switch (cleaned) {
            case "STRING", "VARCHAR", "CHAR", "CHARACTER", "NVARCHAR" -> "VARCHAR";
            case "LONG", "BIGINT", "BIGSERIAL" -> "BIGINT";
            case "INT", "INTEGER", "SMALLINT", "SHORT", "SERIAL" -> "INTEGER";
            case "DECIMAL", "NUMERIC", "BIGDECIMAL" -> "DECIMAL";
            case "BOOL", "BOOLEAN" -> "BOOLEAN";
            case "DATETIME", "TIMESTAMP", "LOCALDATETIME" -> "TIMESTAMP";
            case "DATE", "LOCALDATE" -> "DATE";
            case "TEXT" -> "TEXT";
            default -> null;
        };
    }
    private String id() { String id; do { id = UUID.randomUUID().toString(); } while (!ids.add(id)); return id; }
    private void collectIds(Object value) {
        if (value instanceof Map<?, ?> map) {
            if (map.get("id") instanceof String id) ids.add(id);
            map.values().forEach(this::collectIds);
        } else if (value instanceof List<?> list) list.forEach(this::collectIds);
    }
    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapOrEmpty(Object raw) { return raw instanceof Map<?, ?> ? (Map<String, Object>) raw : Map.of(); }
    private static Map<String, Object> data(Map<String, Object> element) { return mapOrEmpty(element.get("data")); }
    @SuppressWarnings("unchecked")
    public static <T> T copy(T value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((k, v) -> result.put((String) k, copy(v))); return (T) result;
        }
        if (value instanceof List<?> list) {
            List<Object> result = new ArrayList<>(); list.forEach(item -> result.add(copy(item))); return (T) result;
        }
        return value;
    }
}
