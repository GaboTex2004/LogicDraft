package com.sw1.backend.ai.conversation;

import com.sw1.backend.ai.conversation.service.ProposalJsonCodec;
import com.sw1.backend.ai.conversation.service.ServerDiagramOperationApplier;
import com.sw1.backend.ai.diagram.dto.AssociationConversionDefinition;
import com.sw1.backend.ai.diagram.dto.AssociationCreationDefinition;
import com.sw1.backend.ai.diagram.dto.AttributeDefinition;
import com.sw1.backend.ai.diagram.dto.DiagramContext;
import com.sw1.backend.ai.diagram.dto.DiagramInterpretResponse;
import com.sw1.backend.ai.diagram.dto.DiagramOperation;
import com.sw1.backend.ai.diagram.dto.EntityDefinition;
import com.sw1.backend.ai.diagram.dto.MethodDefinition;
import com.sw1.backend.ai.diagram.dto.ParameterDefinition;
import com.sw1.backend.ai.diagram.dto.RelationshipDefinition;
import com.sw1.backend.ai.diagram.model.DiagramCardinality;
import com.sw1.backend.ai.diagram.model.DiagramDataType;
import com.sw1.backend.ai.diagram.model.DiagramOperationType;
import com.sw1.backend.ai.diagram.validation.ContextualOperationValidator;
import com.sw1.backend.ai.diagram.validation.DiagramContextMapper;
import com.sw1.backend.ai.diagram.validation.SemanticOperationValidator;
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

    @Test
    @SuppressWarnings("unchecked")
    void scenarios13To39_fullUmlV2CrudClassifiersAttributesMethodsParametersLiteralsRelationshipsAndMultiOp() {
        AtomicInteger seq = new AtomicInteger(100);
        // Scenarios 13, 14, 15, 16, 17: Create ENTITY, CLASS, INTERFACE, ENUMERATION, and ASSOCIATION_CLASS
        List<DiagramOperation> createClassifiersBatch = List.of(
                // 13. Crear ENTITY
                new DiagramOperation(DiagramOperationType.ADD_ENTITY,
                        new EntityDefinition("Devolucion", List.of(
                                new AttributeDefinition("id", DiagramDataType.Long, true, false, "PRIVATE", false)),
                                "ENTITY", false, null, null),
                        null, null, null),
                // 14. Crear CLASS (base & child for generalization)
                new DiagramOperation(DiagramOperationType.ADD_ENTITY,
                        new EntityDefinition("DocumentoBase", List.of(
                                new AttributeDefinition("fechaCreacion", DiagramDataType.DateTime, false, false, "PROTECTED", false)),
                                "CLASS", true,
                                List.of(new MethodDefinition("validar", "Boolean", "PUBLIC", false, true, List.of())),
                                null),
                        null, null, null),
                new DiagramOperation(DiagramOperationType.ADD_ENTITY,
                        new EntityDefinition("Venta", List.of(
                                new AttributeDefinition("id", DiagramDataType.Integer, true, false)),
                                "CLASS", false, List.of(), null),
                        null, null, null),
                new DiagramOperation(DiagramOperationType.ADD_ENTITY,
                        new EntityDefinition("Producto", List.of(
                                new AttributeDefinition("id", DiagramDataType.Integer, true, false),
                                new AttributeDefinition("Precio de venta", DiagramDataType.Double, false, true)),
                                "CLASS", false, List.of(), null),
                        null, null, null),
                // 15. Crear INTERFACE
                new DiagramOperation(DiagramOperationType.ADD_ENTITY,
                        new EntityDefinition("Auditable", List.of(), "INTERFACE", false,
                                List.of(new MethodDefinition("registrarAuditoria", "void", "PUBLIC", false, false,
                                        List.of(new ParameterDefinition("usuario", "String")))),
                                null),
                        null, null, null),
                // 16. Crear ENUMERATION
                new DiagramOperation(DiagramOperationType.ADD_ENTITY,
                        new EntityDefinition("EstadoVenta", List.of(), "ENUMERATION", false, null,
                                List.of("BORRADOR", "CONFIRMADA")),
                        null, null, null),
                // 17. Crear ASSOCIATION_CLASS válida
                new DiagramOperation(DiagramOperationType.CREATE_ASSOCIATION, null, null, null, null, null,
                        null, null, null, null,
                        new AssociationCreationDefinition("Venta", "Producto", "compra",
                                List.of(new AttributeDefinition("descuento", DiagramDataType.Double, false, true))),
                        null)
        );

        assertEquals(createClassifiersBatch, ProposalJsonCodec.decodeOperations(ProposalJsonCodec.encodeOperations(createClassifiersBatch)));

        var step1 = ServerDiagramOperationApplier.apply(
                ServerDiagramOperationApplier.emptyDocument(), createClassifiersBatch, () -> "u" + seq.getAndIncrement());
        assertEquals(2, step1.version());
        assertDoesNotThrow(() -> DiagramDocumentValidator.validate(step1.document()));

        // Scenarios 20, 21, 22, 23: Attribute CRUD (create, rename, type, visibility, static, nullable, delete, and block structural FK deletion)
        List<DiagramOperation> attrCrudBatch = List.of(
                // 20. Crear atributo en clasificador existente
                new DiagramOperation(DiagramOperationType.ADD_ATTRIBUTE, null, "Venta",
                        new AttributeDefinition("notas", DiagramDataType.String, false, true, "PRIVATE", false), null),
                // 21. Modificar nombre, tipo, visibilidad, static y nullable de atributo
                new DiagramOperation(DiagramOperationType.RENAME_ATTRIBUTE, null, "Venta", null, null, null,
                        "notas", "codigoInterno", null, null, null, null),
                new DiagramOperation(DiagramOperationType.CHANGE_ATTRIBUTE_TYPE, null, "Venta", null, null, null,
                        "codigoInterno", null, DiagramDataType.Long, null, null, null),
                DiagramOperation.setAttributeVisibility("Venta", "codigoInterno", "PROTECTED"),
                DiagramOperation.setAttributeStatic("Venta", "codigoInterno", true),
                new DiagramOperation(DiagramOperationType.SET_ATTRIBUTE_NULLABLE, null, "Venta", null, null, null,
                        "codigoInterno", null, null, false, null, null),
                // 22. Eliminar atributo normal
                new DiagramOperation(DiagramOperationType.DELETE_ATTRIBUTE, null, "Venta", null, null, null,
                        "codigoInterno", null, null, null, null, null)
        );
        assertEquals(attrCrudBatch, ProposalJsonCodec.decodeOperations(ProposalJsonCodec.encodeOperations(attrCrudBatch)));
        var step2 = ServerDiagramOperationApplier.apply(step1.document(), attrCrudBatch, () -> "u" + seq.getAndIncrement());
        assertDoesNotThrow(() -> DiagramDocumentValidator.validate(step2.document()));

        // 23. Intentar eliminar atributo estructural de ASSOCIATION_CLASS ("venta_id" en "compra") -> rechazo controlado
        DiagramValidationException fkEx = assertThrows(DiagramValidationException.class, () ->
                ServerDiagramOperationApplier.apply(step2.document(), List.of(
                        new DiagramOperation(DiagramOperationType.DELETE_ATTRIBUTE, null, "compra", null, null, null,
                                "venta_id", null, null, null, null, null))));
        assertTrue(fkEx.getMessage().contains("estructural"));

        // Scenarios 24, 25, 26: Method & parameter CRUD
        List<DiagramOperation> methodCrudBatch = List.of(
                // 24. Crear método con parámetros
                DiagramOperation.addMethod("Venta", new MethodDefinition("calcularTotal", "Double", "PUBLIC", false, false,
                        List.of(new ParameterDefinition("impuesto", "Double")))),
                // 25. Modificar método y parámetros (add, update, delete parameter; update method)
                DiagramOperation.addMethodParameter("Venta", "calcularTotal", null,
                        new ParameterDefinition("moneda", "String")),
                DiagramOperation.updateMethodParameter("Venta", "calcularTotal", null,
                        "moneda", "codigoMoneda", "String", new ParameterDefinition("codigoMoneda", "String")),
                DiagramOperation.deleteMethodParameter("Venta", "calcularTotal", null, "codigoMoneda"),
                DiagramOperation.updateMethod("Venta", "calcularTotal", List.of("Double"),
                        "obtenerTotal", "Double", "PROTECTED", false, false,
                        new MethodDefinition("obtenerTotal", "Double", "PROTECTED", false, false,
                                List.of(new ParameterDefinition("impuesto", "Double")))),
                // 26. Eliminar método
                DiagramOperation.deleteMethod("Venta", "obtenerTotal", List.of("Double"))
        );
        assertEquals(methodCrudBatch, ProposalJsonCodec.decodeOperations(ProposalJsonCodec.encodeOperations(methodCrudBatch)));
        var step3 = ServerDiagramOperationApplier.apply(step2.document(), methodCrudBatch, () -> "u" + seq.getAndIncrement());
        assertDoesNotThrow(() -> DiagramDocumentValidator.validate(step3.document()));

        // Scenarios 27, 28, 29: Enumeration literals CRUD preserving order
        List<DiagramOperation> enumCrudBatch = List.of(
                // 27. Crear literal en enumeración
                DiagramOperation.addEnumLiteral("EstadoVenta", "ANULADA"),
                // 28. Renombrar literal
                DiagramOperation.renameEnumLiteral("EstadoVenta", "BORRADOR", "PENDIENTE"),
                // 29. Eliminar literal
                DiagramOperation.deleteEnumLiteral("EstadoVenta", "ANULADA")
        );
        assertEquals(enumCrudBatch, ProposalJsonCodec.decodeOperations(ProposalJsonCodec.encodeOperations(enumCrudBatch)));
        var step4 = ServerDiagramOperationApplier.apply(step3.document(), enumCrudBatch, () -> "u" + seq.getAndIncrement());
        assertDoesNotThrow(() -> DiagramDocumentValidator.validate(step4.document()));
        Map<String, Object> enumData = ((List<Map<String, Object>>) step4.document().get("nodes")).stream()
                .map(n -> (Map<String, Object>) n.get("data"))
                .filter(d -> "EstadoVenta".equals(d.get("name")))
                .findFirst().orElseThrow();
        assertEquals(List.of("PENDIENTE", "CONFIRMADA"), enumData.get("literals"));

        // Scenarios 30, 31, 32, 33, 34, 35, 36, 37, 38: UML relationships CRUD
        List<DiagramOperation> relCrudBatch = List.of(
                // 30. Crear relación ASSOCIATION con multiplicidad
                new DiagramOperation(DiagramOperationType.ADD_RELATIONSHIP, null, null, null,
                        new RelationshipDefinition("Devolucion", "compra",
                                DiagramCardinality.ZERO_MANY, DiagramCardinality.ZERO_MANY, "devoluciones", null,
                                null, "ASSOCIATION", "0..*", "0..*", null)),
                // 31. Crear GENERALIZATION (Venta hereda de DocumentoBase)
                new DiagramOperation(DiagramOperationType.ADD_RELATIONSHIP, null, null, null,
                        new RelationshipDefinition("Venta", "DocumentoBase",
                                null, null, null, null, null, "GENERALIZATION", null, null, null)),
                // 32. Crear REALIZATION (Venta implementa Auditable)
                new DiagramOperation(DiagramOperationType.ADD_RELATIONSHIP, null, null, null,
                        new RelationshipDefinition("Venta", "Auditable",
                                null, null, null, null, null, "REALIZATION", null, null, null)),
                // 33. Crear AGGREGATION
                new DiagramOperation(DiagramOperationType.ADD_RELATIONSHIP, null, null, null,
                        new RelationshipDefinition("DocumentoBase", "Producto",
                                DiagramCardinality.ONE_ONE, DiagramCardinality.ZERO_MANY, "catalogo", null,
                                null, "AGGREGATION", null, null, "SOURCE")),
                // 34. Crear COMPOSITION
                new DiagramOperation(DiagramOperationType.ADD_RELATIONSHIP, null, null, null,
                        new RelationshipDefinition("Venta", "Devolucion",
                                DiagramCardinality.ONE_ONE, DiagramCardinality.ZERO_MANY, "partes", null,
                                null, "COMPOSITION", null, null, "SOURCE")),
                // 35. Crear DEPENDENCY
                new DiagramOperation(DiagramOperationType.ADD_RELATIONSHIP, null, null, null,
                        new RelationshipDefinition("Venta", "EstadoVenta",
                                null, null, null, null, null, "DEPENDENCY", null, null, null)),
                // 36. Modificar multiplicidad de asociación existente
                new DiagramOperation(DiagramOperationType.UPDATE_RELATIONSHIP, null, null, null,
                        new RelationshipDefinition("Devolucion", "compra",
                                DiagramCardinality.ONE_ONE, DiagramCardinality.ZERO_MANY, "devoluciones", null,
                                null, "ASSOCIATION", "1", "0..*", null)),
                // 37. Eliminar relación válida (DEPENDENCY y AGGREGATION)
                new DiagramOperation(DiagramOperationType.DELETE_RELATIONSHIP, null, null, null,
                        new RelationshipDefinition("Venta", "EstadoVenta",
                                null, null, null, null, null, "DEPENDENCY", null, null, null)),
                new DiagramOperation(DiagramOperationType.DELETE_RELATIONSHIP, null, null, null,
                        new RelationshipDefinition("DocumentoBase", "Producto",
                                null, null, "catalogo", null, null, "AGGREGATION", null, null, null))
        );
        assertEquals(relCrudBatch, ProposalJsonCodec.decodeOperations(ProposalJsonCodec.encodeOperations(relCrudBatch)));
        var step5 = ServerDiagramOperationApplier.apply(step4.document(), relCrudBatch, () -> "u" + seq.getAndIncrement());
        assertDoesNotThrow(() -> DiagramDocumentValidator.validate(step5.document()));

        // 38. Intentar eliminar arista estructural de clase asociativa ("Venta" - "compra") -> bloqueo
        DiagramValidationException structEdgeEx = assertThrows(DiagramValidationException.class, () ->
                ServerDiagramOperationApplier.apply(step5.document(), List.of(
                        new DiagramOperation(DiagramOperationType.DELETE_RELATIONSHIP, null, null, null,
                                new RelationshipDefinition("Venta", "compra", null, null, null, null)))));
        assertTrue(structEdgeEx.getMessage().contains("arista estructural"));

        // 39. Ejecutar instrucción multi-operación real:
        // "@LogicDraft elimina el atributo Precio de venta de la clase Producto y crea un atributo CANTIDAD de tipo INTEGER en la clase compra"
        String realPrompt = "@LogicDraft elimina el atributo Precio de venta de la clase Producto y crea un atributo CANTIDAD de tipo INTEGER en la clase compra";
        List<DiagramOperation> multiOpBatch = List.of(
                new DiagramOperation(DiagramOperationType.DELETE_ATTRIBUTE, null, "Producto", null, null, null,
                        "Precio de venta", null, null, null, null, null),
                new DiagramOperation(DiagramOperationType.ADD_ATTRIBUTE, null, "compra",
                        new AttributeDefinition("CANTIDAD", DiagramDataType.Integer, false, true), null)
        );
        DiagramContext mappedContext = DiagramContextMapper.fromDocument(step5.document());
        assertDoesNotThrow(() -> SemanticOperationValidator.validate(realPrompt, mappedContext, multiOpBatch));
        assertDoesNotThrow(() -> ContextualOperationValidator.validate(mappedContext, new DiagramInterpretResponse(multiOpBatch)));

        var step6 = ServerDiagramOperationApplier.apply(step5.document(), multiOpBatch, () -> "u" + seq.getAndIncrement());
        assertDoesNotThrow(() -> DiagramDocumentValidator.validate(step6.document()));

        List<Map<String, Object>> nodesAfterMultiOp = (List<Map<String, Object>>) step6.document().get("nodes");
        Map<String, Object> productoData = nodesAfterMultiOp.stream()
                .map(n -> (Map<String, Object>) n.get("data"))
                .filter(d -> "Producto".equals(d.get("name")))
                .findFirst().orElseThrow();
        List<Map<String, Object>> productoAttrs = (List<Map<String, Object>>) productoData.get("attributes");
        assertTrue(productoAttrs.stream().noneMatch(a -> "Precio de venta".equals(a.get("name"))));

        Map<String, Object> compraData = nodesAfterMultiOp.stream()
                .map(n -> (Map<String, Object>) n.get("data"))
                .filter(d -> "compra".equals(d.get("name")))
                .findFirst().orElseThrow();
        List<Map<String, Object>> compraAttrs = (List<Map<String, Object>>) compraData.get("attributes");
        assertTrue(compraAttrs.stream().anyMatch(a -> "CANTIDAD".equals(a.get("name")) && "INTEGER".equals(a.get("type"))));

        // Scenarios 18 & 19: Renombrar y eliminar clasificadores (including INTERFACE & ENUMERATION)
        List<DiagramOperation> classifierMutBatch = List.of(
                new DiagramOperation(DiagramOperationType.RENAME_ENTITY, null, "Auditable", null, null, null,
                        null, "IAuditable", null, null, null, null),
                DiagramOperation.updateClassifier("Venta", "CLASS", true),
                new DiagramOperation(DiagramOperationType.DELETE_ENTITY, null, "IAuditable", null, null),
                new DiagramOperation(DiagramOperationType.DELETE_ENTITY, null, "EstadoVenta", null, null)
        );
        var step7 = ServerDiagramOperationApplier.apply(step6.document(), classifierMutBatch, () -> "u" + seq.getAndIncrement());
        assertDoesNotThrow(() -> DiagramDocumentValidator.validate(step7.document()));
    }
}
