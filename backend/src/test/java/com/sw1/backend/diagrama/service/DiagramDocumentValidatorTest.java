package com.sw1.backend.diagrama.service;

import com.sw1.backend.diagrama.validation.DiagramDocumentValidator;
import com.sw1.backend.diagrama.validation.DiagramValidationException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DiagramDocumentValidatorTest {
    @Test
    void acceptsCurrentAndLegacyRelationships() {
        Map<String, Object> current = document(
                List.of(node("a", "Alumno"), node("m", "Materia")),
                List.of(edge("r1", "a", "m", Map.of(
                        "sourceCardinality", "ONE_ONE", "targetCardinality", "ZERO_MANY"))));
        Map<String, Object> legacyEdge = new LinkedHashMap<>();
        legacyEdge.put("source", "a");
        legacyEdge.put("target", "m");
        legacyEdge.put("data", Map.of("relationshipType", "ONE_TO_MANY"));
        Map<String, Object> legacy = document(currentNodes(current), List.of(legacyEdge));

        assertDoesNotThrow(() -> DiagramDocumentValidator.validate(current));
        assertDoesNotThrow(() -> DiagramDocumentValidator.validate(legacy));
    }

    @Test
    void rejectsDuplicateIdsBrokenReferencesAndPartialCardinality() {
        assertThrows(DiagramValidationException.class, () -> DiagramDocumentValidator.validate(
                document(List.of(node("a", "A"), node("a", "B")), List.of())));
        assertThrows(DiagramValidationException.class, () -> DiagramDocumentValidator.validate(
                document(List.of(node("a", "A")), List.of(edge("r", "a", "missing", Map.of())))));
        assertThrows(DiagramValidationException.class, () -> DiagramDocumentValidator.validate(
                document(List.of(node("a", "A"), node("b", "B")),
                        List.of(edge("r", "a", "b", Map.of("sourceCardinality", "ONE_ONE"))))));
    }

    @Test
    void rejectsInvalidAttributesWithoutRewritingInput() {
        Map<String, Object> node = node("a", "Alumno");
        map(node.get("data")).put("attributes", List.of(Map.of(
                "id", "id", "name", "id", "type", "EXECUTABLE", "primaryKey", true)));
        Map<String, Object> document = document(List.of(node), List.of());
        assertThrows(DiagramValidationException.class, () -> DiagramDocumentValidator.validate(document));
    }

    private static Map<String, Object> document(List<Map<String, Object>> nodes, List<Map<String, Object>> edges) {
        return Map.of("version", 1, "nodes", nodes, "edges", edges);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> currentNodes(Map<String, Object> document) {
        return (List<Map<String, Object>>) document.get("nodes");
    }

    private static Map<String, Object> node(String id, String name) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", id);
        data.put("name", name);
        data.put("attributes", List.of());
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", id);
        node.put("type", "entity");
        node.put("position", Map.of("x", 10, "y", 20));
        node.put("data", data);
        return node;
    }

    private static Map<String, Object> edge(String id, String source, String target, Map<String, Object> data) {
        return Map.of("id", id, "source", source, "target", target, "type", "relationship", "data", data);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }
}
