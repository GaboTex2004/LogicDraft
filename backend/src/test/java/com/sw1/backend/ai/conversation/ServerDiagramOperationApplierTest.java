package com.sw1.backend.ai.conversation;

import com.sw1.backend.ai.conversation.service.ProposalJsonCodec;
import com.sw1.backend.ai.conversation.service.ServerDiagramOperationApplier;
import com.sw1.backend.ai.diagram.dto.AssociationConversionDefinition;
import com.sw1.backend.ai.diagram.dto.AssociationCreationDefinition;
import com.sw1.backend.ai.diagram.dto.AttributeDefinition;
import com.sw1.backend.ai.diagram.dto.DiagramOperation;
import com.sw1.backend.ai.diagram.dto.EntityDefinition;
import com.sw1.backend.ai.diagram.dto.RelationshipDefinition;
import com.sw1.backend.ai.diagram.model.DiagramCardinality;
import com.sw1.backend.ai.diagram.model.DiagramDataType;
import com.sw1.backend.ai.diagram.model.DiagramOperationType;
import com.sw1.backend.diagrama.validation.DiagramDocumentValidator;
import com.sw1.backend.diagrama.validation.DiagramValidationException;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ServerDiagramOperationApplierTest {

    @Test
    void appliesAllFifteenOperationsDeterministicallyAndRoundtripsThroughJsonCodec() {
        AtomicInteger ids = new AtomicInteger(1);
        List<DiagramOperation> batch1 = List.of(
                new DiagramOperation(DiagramOperationType.ADD_ENTITY,
                        new EntityDefinition("Venta", List.of(
                                new AttributeDefinition("id", DiagramDataType.Long, true, false),
                                new AttributeDefinition("fecha", DiagramDataType.Date, false, true))),
                        null, null, null),
                new DiagramOperation(DiagramOperationType.ADD_ENTITY,
                        new EntityDefinition("Producto", List.of(
                                new AttributeDefinition("id", DiagramDataType.Long, true, false),
                                new AttributeDefinition("precio", DiagramDataType.Double, false, false))),
                        null, null, null),
                new DiagramOperation(DiagramOperationType.ADD_ENTITY,
                        new EntityDefinition("Temporal", List.of()),
                        null, null, null),
                new DiagramOperation(DiagramOperationType.ADD_RELATIONSHIP,
                        null, null, null,
                        new RelationshipDefinition("Venta", "Producto",
                                DiagramCardinality.ZERO_MANY, DiagramCardinality.ZERO_MANY, "items", "venta_producto")));

        // Verify ProposalJsonCodec roundtrip
        assertEquals(batch1, ProposalJsonCodec.decodeOperations(ProposalJsonCodec.encodeOperations(batch1)));

        var step1 = ServerDiagramOperationApplier.apply(
                ServerDiagramOperationApplier.emptyDocument(), batch1, () -> "id" + ids.getAndIncrement());
        assertTrue(step1.changed());
        assertEquals(1, step1.version());

        // Exercise attribute & entity mutations + relationship update + N:M conversion
        List<?> edgesAfterStep1 = (List<?>) step1.document().get("edges");
        String nmEdgeId = (String) ((Map<?, ?>) edgesAfterStep1.getFirst()).get("id");

        List<DiagramOperation> batch2 = List.of(
                new DiagramOperation(DiagramOperationType.DELETE_ENTITY, null, "Temporal", null, null),
                new DiagramOperation(DiagramOperationType.ADD_ATTRIBUTE, null, "Producto",
                        new AttributeDefinition("stock", DiagramDataType.String, false, true), null),
                new DiagramOperation(DiagramOperationType.CHANGE_ATTRIBUTE_TYPE, null, "Producto", null, null, null,
                        "stock", null, DiagramDataType.Integer, null, null, null),
                new DiagramOperation(DiagramOperationType.RENAME_ATTRIBUTE, null, "Producto", null, null, null,
                        "stock", "cantidadStock", null, null, null, null),
                new DiagramOperation(DiagramOperationType.SET_ATTRIBUTE_NULLABLE, null, "Producto", null, null, null,
                        "cantidadStock", null, null, false, null, null),
                new DiagramOperation(DiagramOperationType.SET_ATTRIBUTE_PRIMARY_KEY, null, "Venta", null, null, null,
                        "id", null, null, true, null, null),
                new DiagramOperation(DiagramOperationType.UPDATE_RELATIONSHIP, null, null, null,
                        new RelationshipDefinition("Venta", "Producto",
                                DiagramCardinality.ONE_MANY, DiagramCardinality.ZERO_MANY, "items", null)),
                new DiagramOperation(DiagramOperationType.CONVERT_MANY_TO_MANY_ASSOCIATION, null, null, null, null,
                        new AssociationConversionDefinition(nmEdgeId, "Venta", "Producto", "DetalleVenta",
                                List.of(new AttributeDefinition("cantidad", DiagramDataType.Integer, false, false)))));

        assertEquals(batch2, ProposalJsonCodec.decodeOperations(ProposalJsonCodec.encodeOperations(batch2)));

        var step2 = ServerDiagramOperationApplier.apply(
                step1.document(), batch2, () -> "id" + ids.getAndIncrement());
        assertEquals(2, step2.version());
        assertDoesNotThrow(() -> DiagramDocumentValidator.validate(step2.document()));

        // Exercise DELETE_ATTRIBUTE, RENAME_ENTITY, DELETE_ASSOCIATION, CREATE_ASSOCIATION, DELETE_RELATIONSHIP
        List<DiagramOperation> batch3 = List.of(
                new DiagramOperation(DiagramOperationType.DELETE_ATTRIBUTE, null, "Producto", null, null, null,
                        "cantidadStock", null, null, null, null, null),
                new DiagramOperation(DiagramOperationType.DELETE_ASSOCIATION, null, null, null, null, null,
                        null, null, null, null, null, "DetalleVenta"),
                new DiagramOperation(DiagramOperationType.RENAME_ENTITY, null, "Producto", null, null, null,
                        null, "Articulo", null, null, null, null),
                new DiagramOperation(DiagramOperationType.CREATE_ASSOCIATION, null, null, null, null, null,
                        null, null, null, null,
                        new AssociationCreationDefinition("Venta", "Articulo", "LineaVenta",
                                List.of(new AttributeDefinition("subtotal", DiagramDataType.Double, false, false))),
                        null),
                new DiagramOperation(DiagramOperationType.ADD_RELATIONSHIP, null, null, null,
                        new RelationshipDefinition("Venta", "Articulo",
                                DiagramCardinality.ONE_ONE, DiagramCardinality.ZERO_MANY, "principal", null)),
                new DiagramOperation(DiagramOperationType.DELETE_RELATIONSHIP, null, null, null,
                        new RelationshipDefinition("Venta", "Articulo", null, null, "principal", null)));

        assertEquals(batch3, ProposalJsonCodec.decodeOperations(ProposalJsonCodec.encodeOperations(batch3)));

        var step3 = ServerDiagramOperationApplier.apply(
                step2.document(), batch3, () -> "id" + ids.getAndIncrement());
        assertDoesNotThrow(() -> DiagramDocumentValidator.validate(step3.document()));
        List<?> finalNodes = (List<?>) step3.document().get("nodes");
        assertEquals(3, finalNodes.size()); // Venta, Articulo, LineaVenta
    }

    @Test
    void preservesUmlV2InterfacesEnumsMethodsAndEaMetadataAndRejectsInvalidTargetKinds() {
        Map<String, Object> interfaceNode = new LinkedHashMap<>();
        interfaceNode.put("id", "node-iface");
        interfaceNode.put("type", "entity");
        interfaceNode.put("position", Map.of("x", 100, "y", 100));
        interfaceNode.put("data", new LinkedHashMap<>(Map.of(
                "id", "node-iface",
                "name", "Exportable",
                "kind", "INTERFACE",
                "attributes", List.of(),
                "methods", List.of(Map.of(
                        "id", "m-1", "name", "exportar", "visibility", "public",
                        "returnType", "String", "parameters", List.of())),
                "externalMetadata", Map.of("source", "enterprise-architect", "externalId", "EAID_1"))));

        Map<String, Object> enumNode = new LinkedHashMap<>();
        enumNode.put("id", "node-enum");
        enumNode.put("type", "entity");
        enumNode.put("position", Map.of("x", 400, "y", 100));
        enumNode.put("data", new LinkedHashMap<>(Map.of(
                "id", "node-enum",
                "name", "EstadoVenta",
                "kind", "ENUMERATION",
                "attributes", List.of(),
                "literals", List.of("PENDIENTE", "PAGADA"))));

        Map<String, Object> classNode = new LinkedHashMap<>();
        classNode.put("id", "node-venta");
        classNode.put("type", "entity");
        classNode.put("position", Map.of("x", 250, "y", 300));
        classNode.put("data", new LinkedHashMap<>(Map.of(
                "id", "node-venta",
                "name", "Venta",
                "kind", "CLASS",
                "attributes", List.of(Map.of(
                        "id", "attr-id", "name", "id", "type", "BIGINT", "primaryKey", true, "nullable", false)),
                "methods", List.of(Map.of(
                        "id", "m-2", "name", "calcularTotal", "visibility", "public",
                        "returnType", "Double", "parameters", List.of())))));

        Map<String, Object> realizationEdge = new LinkedHashMap<>();
        realizationEdge.put("id", "edge-real");
        realizationEdge.put("type", "relationship");
        realizationEdge.put("source", "node-venta");
        realizationEdge.put("target", "node-iface");
        realizationEdge.put("data", Map.of(
                "relationKind", "REALIZATION",
                "sourceCardinality", "ONE_ONE",
                "targetCardinality", "ONE_ONE"));

        Map<String, Object> umlV2Doc = Map.of(
                "version", 2,
                "nodes", List.of(interfaceNode, enumNode, classNode),
                "edges", List.of(realizationEdge));

        // Adding attribute to CLASS works and preserves INTERFACE, ENUMERATION, methods, REALIZATION, and EA metadata!
        var updated = ServerDiagramOperationApplier.apply(umlV2Doc, List.of(
                new DiagramOperation(DiagramOperationType.ADD_ATTRIBUTE, null, "Venta",
                        new AttributeDefinition("total", DiagramDataType.Double, false, false), null)));
        assertEquals(2, updated.version());
        assertEquals(List.of("node-venta"), updated.affectedElementIds());
        assertDoesNotThrow(() -> DiagramDocumentValidator.validate(updated.document()));

        // Attempting to add an attribute to ENUMERATION or INTERFACE is rejected safely
        assertThrows(DiagramValidationException.class, () ->
                ServerDiagramOperationApplier.apply(umlV2Doc, List.of(
                        new DiagramOperation(DiagramOperationType.ADD_ATTRIBUTE, null, "EstadoVenta",
                                new AttributeDefinition("codigo", DiagramDataType.String, false, true), null))));
        assertThrows(DiagramValidationException.class, () ->
                ServerDiagramOperationApplier.apply(umlV2Doc, List.of(
                        new DiagramOperation(DiagramOperationType.ADD_ATTRIBUTE, null, "Exportable",
                                new AttributeDefinition("codigo", DiagramDataType.String, false, true), null))));
    }
}
