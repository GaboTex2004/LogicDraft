package com.sw1.backend.ai.client;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sw1.backend.ai.diagram.dto.*;
import com.sw1.backend.ai.diagram.model.*;
import com.sw1.backend.ai.diagram.validation.ContextualOperationValidator;
import com.sw1.backend.ai.diagram.validation.DiagramOperationValidator;
import java.util.*;
import org.junit.jupiter.api.Test;

class DiagramCrudOperationValidatorTest {
    private static Map<String, Object> attribute(String name, String type) {
        return Map.of("name", name, "dataType", type, "primaryKey", false, "nullable", true);
    }

    @Test
    void validatesAndSerializesTheClosedCrudContract() throws Exception {
        List<Map<String, Object>> operations = List.of(
                Map.of("type", "DELETE_ENTITY", "entityName", "Obsoleto"),
                Map.of("type", "RENAME_ENTITY", "entityName", "Cliente", "newName", "Persona"),
                Map.of("type", "DELETE_ATTRIBUTE", "entityName", "Persona", "attributeName", "telefono"),
                Map.of("type", "RENAME_ATTRIBUTE", "entityName", "Persona", "attributeName", "nombre", "newName", "razonSocial"),
                Map.of("type", "CHANGE_ATTRIBUTE_TYPE", "entityName", "Producto", "attributeName", "precio", "dataType", "Double"),
                Map.of("type", "SET_ATTRIBUTE_PRIMARY_KEY", "entityName", "Persona", "attributeName", "id", "value", true),
                Map.of("type", "SET_ATTRIBUTE_NULLABLE", "entityName", "Persona", "attributeName", "correo", "value", true),
                Map.of("type", "DELETE_RELATIONSHIP", "relationship", Map.of(
                        "sourceEntity", "Persona", "targetEntity", "Venta", "name", "compras")),
                Map.of("type", "UPDATE_RELATIONSHIP", "relationship", Map.of(
                        "sourceEntity", "Persona", "targetEntity", "Venta", "name", "compras",
                        "sourceCardinality", "ONE_ONE", "targetCardinality", "ONE_ONE")),
                Map.of("type", "CREATE_ASSOCIATION", "association", Map.of(
                        "sourceEntity", "Producto", "targetEntity", "Venta",
                        "associationEntityName", "DetalleVenta", "attributes", List.of(attribute("cantidad", "Integer")))),
                Map.of("type", "DELETE_ASSOCIATION", "associationEntityName", "DetalleAnterior")
        );
        DiagramInterpretResponse response = DiagramOperationValidator.validate(Map.of("operations", operations));
        assertEquals(11, response.operations().size());
        assertEquals(DiagramDataType.Double, response.operations().get(4).dataType());
        assertEquals(DiagramCardinality.ONE_ONE, response.operations().get(8).relationship().targetCardinality());
        String json = new ObjectMapper().writeValueAsString(response);
        assertTrue(json.contains("\"relationship\""));
        assertFalse(json.contains("relationshipReference"));
        assertFalse(json.contains("relationshipUpdate"));
    }

    @Test
    void rejectsUnknownIncompleteTypeAndCardinality() {
        List<Map<String, Object>> invalid = List.of(
                Map.of("type", "UNKNOWN"),
                Map.of("type", "DELETE_ATTRIBUTE", "entityName", "A"),
                Map.of("type", "CHANGE_ATTRIBUTE_TYPE", "entityName", "A", "attributeName", "x", "dataType", "SQL"),
                Map.of("type", "UPDATE_RELATIONSHIP", "relationship", Map.of(
                        "sourceEntity", "A", "targetEntity", "B", "sourceCardinality", "OTHER", "targetCardinality", "ONE_ONE"))
        );
        for (Map<String, Object> operation : invalid) {
            assertThrows(AiServiceException.class,
                    () -> DiagramOperationValidator.validate(Map.of("operations", List.of(operation))));
        }
    }

    @Test
    void contextualValidationResolvesASequentialCrudPlan() {
        AttributeDefinition id = new AttributeDefinition("id", DiagramDataType.Integer, true, false);
        DiagramContext context = new DiagramContext(List.of(
                new EntityDefinition("Producto", List.of(id)),
                new EntityDefinition("Venta", List.of(id))), List.of());
        List<Map<String, Object>> raw = List.of(
                Map.of("type", "RENAME_ENTITY", "entityName", "Producto", "newName", "Item"),
                Map.of("type", "ADD_ATTRIBUTE", "entityName", "Item", "attribute", attribute("stock", "Integer")),
                Map.of("type", "CREATE_ASSOCIATION", "association", Map.of(
                        "sourceEntity", "Item", "targetEntity", "Venta", "associationEntityName", "DetalleVenta",
                        "attributes", List.of(attribute("cantidad", "Integer"))))
        );
        DiagramInterpretResponse parsed = DiagramOperationValidator.validate(Map.of("operations", raw));
        assertDoesNotThrow(() -> ContextualOperationValidator.validate(context, parsed));
    }

    @Test
    void ambiguousRelationshipRequiresAName() {
        EntityDefinition a = new EntityDefinition("A", List.of());
        EntityDefinition b = new EntityDefinition("B", List.of());
        DiagramContext context = new DiagramContext(List.of(a, b), List.of(
                new RelationshipDefinition("A", "B", DiagramCardinality.ONE_ONE, DiagramCardinality.ONE_ONE, "x", null),
                new RelationshipDefinition("A", "B", DiagramCardinality.ONE_ONE, DiagramCardinality.ZERO_MANY, "y", null)));
        DiagramInterpretResponse plan = DiagramOperationValidator.validate(Map.of("operations", List.of(
                Map.of("type", "DELETE_RELATIONSHIP", "relationship", Map.of("sourceEntity", "A", "targetEntity", "B")))));
        AiServiceException error = assertThrows(AiServiceException.class,
                () -> ContextualOperationValidator.validate(context, plan));
        assertTrue(error.getMessage().contains("varias relaciones"));
    }
}
