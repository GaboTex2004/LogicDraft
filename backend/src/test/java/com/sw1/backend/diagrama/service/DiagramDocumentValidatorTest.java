package com.sw1.backend.diagrama.service;

import com.sw1.backend.diagrama.validation.DiagramDocumentValidator;
import com.sw1.backend.diagrama.validation.DiagramValidationException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void acceptsUmlV2AndRejectsInvalidRealizationAndDuplicateMethodIds() {
        Map<String, Object> implementation = node("service", "EmailService");
        map(implementation.get("data")).put("kind", "CLASS");
        map(implementation.get("data")).put("methods", List.of(method("m1", "enviar")));
        Map<String, Object> contract = node("contract", "Notificable");
        map(contract.get("data")).put("kind", "INTERFACE");
        map(contract.get("data")).put("methods", List.of(method("m2", "enviar")));
        Map<String, Object> realization = edge("r", "service", "contract", Map.of(
                "sourceCardinality", "ONE_ONE", "targetCardinality", "ONE_ONE", "relationKind", "REALIZATION"));
        assertDoesNotThrow(() -> DiagramDocumentValidator.validate(
                Map.of("version", 2, "nodes", List.of(implementation, contract), "edges", List.of(realization))));

        map(contract.get("data")).put("kind", "CLASS");
        assertThrows(DiagramValidationException.class, () -> DiagramDocumentValidator.validate(
                Map.of("version", 2, "nodes", List.of(implementation, contract), "edges", List.of(realization))));
        map(implementation.get("data")).put("methods", List.of(method("same", "a"), method("same", "b")));
        assertThrows(DiagramValidationException.class, () -> DiagramDocumentValidator.validate(
                Map.of("version", 2, "nodes", List.of(implementation), "edges", List.of())));
    }

    @Test
    void rejectsEnumerationAttributesAndInvalidRelationshipDirections() {
        Map<String, Object> enumeration = node("state", "Estado");
        map(enumeration.get("data")).put("kind", "ENUMERATION");
        map(enumeration.get("data")).put("literals", List.of("ACTIVO"));
        map(enumeration.get("data")).put("attributes", List.of(Map.of(
                "id", "wrong", "name", "ACTIVO", "type", "VARCHAR", "primaryKey", false)));
        DiagramValidationException error = assertThrows(DiagramValidationException.class,
                () -> DiagramDocumentValidator.validate(Map.of("version", 2, "nodes", List.of(enumeration), "edges", List.of())));
        assertTrue(error.getMessage().contains("literales, no atributos"));

        Map<String, Object> contract = node("contract", "Contrato");
        map(contract.get("data")).put("kind", "INTERFACE");
        Map<String, Object> implementation = node("impl", "Implementacion");
        map(implementation.get("data")).put("kind", "CLASS");
        Map<String, Object> reversed = edge("r", "contract", "impl", Map.of(
                "sourceCardinality", "ONE_ONE", "targetCardinality", "ONE_ONE", "relationKind", "REALIZATION"));
        error = assertThrows(DiagramValidationException.class,
                () -> DiagramDocumentValidator.validate(Map.of("version", 2,
                        "nodes", List.of(contract, implementation), "edges", List.of(reversed))));
        assertTrue(error.getMessage().contains("partir de una clase"));
    }

    @Test
    void rejectsAssociationAttributeThatDuplicatesAStructuralForeignKey() {
        Map<String, Object> product = node("product", "Producto");
        Map<String, Object> sale = node("sale", "Ventas");
        Map<String, Object> associationClass = node("line", "Class1");
        Map<String, Object> data = map(associationClass.get("data"));
        data.put("kind", "ASSOCIATION_CLASS");
        data.put("attributes", List.of(
                Map.of("id", "id", "name", "id", "type", "INTEGER", "primaryKey", true,
                        "nullable", false),
                Map.of("id", "product-fk", "name", "producto_id", "type", "INTEGER",
                        "primaryKey", false, "nullable", false)));
        data.put("association", Map.of(
                "kind", "MANY_TO_MANY_ASSOCIATION", "tableName", "class1", "uniquePair", true,
                "endpoints", List.of(
                        Map.of("role", "SOURCE", "entityId", "product", "relationshipId", "product-line",
                                "foreignKeyName", "producto_id"),
                        Map.of("role", "TARGET", "entityId", "sale", "relationshipId", "sale-line",
                                "foreignKeyName", "ventas_id"))));
        Map<String, Object> document = Map.of("version", 2,
                "nodes", List.of(product, sale, associationClass),
                "edges", List.of(
                        edge("product-line", "product", "line", Map.of("sourceCardinality", "ONE_ONE",
                                "targetCardinality", "ZERO_MANY", "relationKind", "ASSOCIATION")),
                        edge("sale-line", "sale", "line", Map.of("sourceCardinality", "ONE_ONE",
                                "targetCardinality", "ZERO_MANY", "relationKind", "ASSOCIATION"))));

        DiagramValidationException error = assertThrows(DiagramValidationException.class,
                () -> DiagramDocumentValidator.validate(document));

        assertTrue(error.getMessage().contains("producto_id"));
        assertTrue(error.getMessage().contains("clave foranea estructural"));
    }

    private static Map<String, Object> method(String id, String name) {
        return Map.of("id", id, "name", name, "visibility", "public", "returnType", "void", "parameters", List.of());
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
