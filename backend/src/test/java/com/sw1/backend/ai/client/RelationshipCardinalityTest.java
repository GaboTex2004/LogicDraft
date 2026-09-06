package com.sw1.backend.ai.client;

import com.sw1.backend.ai.diagram.dto.*;
import com.sw1.backend.ai.diagram.model.*;
import com.sw1.backend.ai.diagram.validation.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.sw1.backend.ai.diagram.model.DiagramCardinality.*;

class RelationshipCardinalityTest {
    DiagramContext empty = new DiagramContext(List.of(), List.of());
    DiagramOperation entity(String name) {
        return new DiagramOperation(DiagramOperationType.ADD_ENTITY, new EntityDefinition(name, List.of()), null, null, null);
    }
    DiagramOperation relation(String source, String target, DiagramCardinality a, DiagramCardinality b) {
        return new DiagramOperation(DiagramOperationType.ADD_RELATIONSHIP, null, null, null, new RelationshipDefinition(source, target, a, b));
    }
    Map<String, Object> node(String id, String name) {
        return Map.of("id", id, "position", Map.of("x", 1, "y", 2), "data", Map.of("name", name, "attributes", List.of()));
    }
    DiagramContext projected(Map<String, Object> data) {
        return DiagramContextMapper.fromDocument(Map.of("version", 1, "nodes", List.of(node("a", "Categoria"), node("b", "Producto")),
            "edges", List.of(Map.of("source", "a", "target", "b", "data", data))));
    }
    @Test void allEnumsPassStrictValidator() {
        for (var a : DiagramCardinality.values()) for (var b : DiagramCardinality.values()) {
            var raw = Map.<String, Object>of("operations", List.of(Map.of("type", "ADD_RELATIONSHIP", "relationship",
                Map.of("sourceEntity", "Categoria", "targetEntity", "Producto", "sourceCardinality", a.name(), "targetCardinality", b.name()))));
            assertEquals(a, DiagramOperationValidator.validate(raw).operations().get(0).relationship().sourceCardinality());
        }
    }
    @Test void invalidEnumsMissingAndExtraFieldsRejected() {
        for (var attrs : List.of(Map.of("sourceCardinality", "OTHER", "targetCardinality", "ONE_ONE"),
                Map.of("sourceCardinality", "ONE_ONE"), Map.of("relationshipType", "ONE_TO_ONE"),
                Map.of("sourceCardinality", "ONE_ONE", "targetCardinality", "ONE_ONE", "extra", "bad"))) {
            var rel = new HashMap<String, Object>(attrs); rel.put("sourceEntity", "A"); rel.put("targetEntity", "B");
            assertThrows(AiServiceException.class, () -> DiagramOperationValidator.validate(Map.of("operations", List.of(Map.of("type", "ADD_RELATIONSHIP", "relationship", rel)))));
        }
    }
    @Test void sequentialBatchSupportsEntitiesThenRelation() {
        var result = ContextualOperationValidator.validate(empty, new DiagramInterpretResponse(List.of(entity("Categoria"), entity("Producto"), relation("CATEGORIA", "producto", ONE_ONE, ZERO_MANY))));
        assertEquals(3, result.operations().size());
        assertTrue(empty.entities().isEmpty());
    }
    @Test void missingAndSelfEndpointsFail409() {
        for (var rel : List.of(relation("Categoria", "Missing", ONE_ONE, ONE_ONE), relation("Categoria", "CATEGORIA", ONE_ONE, ONE_ONE))) {
            var error = assertThrows(AiServiceException.class, () -> ContextualOperationValidator.validate(empty, new DiagramInterpretResponse(List.of(entity("Categoria"), rel))));
            assertEquals(org.springframework.http.HttpStatus.CONFLICT, error.getStatus());
        }
    }
    @Test void reversedEquivalentDeduplicatesButDifferentMinimumDoesNot() {
        var result = ContextualOperationValidator.validate(empty, new DiagramInterpretResponse(List.of(entity("Categoria"), entity("Producto"),
            relation("Categoria", "Producto", ONE_ONE, ZERO_MANY), relation("producto", "categoria", ZERO_MANY, ONE_ONE),
            relation("Categoria", "Producto", ONE_ONE, ONE_MANY))));
        assertEquals(4, result.operations().size());
    }
    @Test void legacyAndUnspecifiedEdgesAreCompatible() {
        for (var type : RelationshipType.values()) {
            var expected = RelationshipDefinition.fromLegacy("Categoria", "Producto", type);
            assertEquals(expected, projected(Map.of("relationshipType", type.name())).relationships().get(0));
        }
        assertEquals(ONE_ONE, projected(Map.of()).relationships().get(0).targetCardinality());
    }
    @Test void newMetadataHasPriorityAndProjectionOmitsUI() {
        var context = projected(Map.of("relationshipType", "ONE_TO_MANY", "sourceCardinality", "ZERO_ONE", "targetCardinality", "ONE_MANY"));
        assertEquals(ZERO_ONE, context.relationships().get(0).sourceCardinality());
        assertEquals(ONE_MANY, context.relationships().get(0).targetCardinality());
        assertFalse(context.toString().contains("position"));
        assertThrows(AiServiceException.class, () -> projected(Map.of("sourceCardinality", "ONE_ONE")));
    }
}
