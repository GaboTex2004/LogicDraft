package com.sw1.backend.ai.image;

import com.sw1.backend.diagrama.validation.DiagramDocumentValidator;
import com.sw1.backend.diagrama.validation.DiagramValidationException;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class ImageDiagramReconcilerTest {
    static Map<String, Object> m(Object... pairs) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) result.put((String) pairs[i], pairs[i + 1]);
        return result;
    }
    static Map<String, Object> node(String id, String name, String kind) {
        return m("id", id, "type", "entity", "position", m("x", 47, "y", 83), "data",
                m("id", id, "name", name, "kind", kind, "attributes", List.of(), "methods", List.of(), "width", 321));
    }
    static Map<String, Object> base() { return m("version", 2, "nodes", List.of(node("c", "Cliente", "CLASS")), "edges", List.of()); }
    static Map<String, Object> classifier(String name, String kind, List<?> attrs) {
        return m("type", "ADD_CLASSIFIER", "name", name, "kind", kind, "attributes", attrs, "methods", List.of(), "literals", List.of());
    }
    static Map<String, Object> recognition(Map<String, Object>... ops) { return m("operations", List.of(ops), "warnings", List.of()); }
    static Map<String, Object> relation(String source, String target) {
        return m("type", "ADD_RELATIONSHIP", "source", source, "target", target, "relationKind", "ASSOCIATION",
                "sourceMultiplicity", "1", "targetMultiplicity", "0..*");
    }
    @SuppressWarnings("unchecked")
    static Map<String, Object> firstData(Map<String, Object> document) {
        return (Map<String, Object>) ((Map<?, ?>) ((List<?>) document.get("nodes")).getFirst()).get("data");
    }

    @Test void addsNewClassWithoutTouchingExistingNode() {
        var original = base(); var before = ImageDiagramReconciler.copy(original);
        var result = ImageDiagramReconciler.reconcile(original, recognition(classifier("Pedido", "CLASS", List.of())));
        assertTrue(result.canApply()); assertEquals(2, ((List<?>) result.document().get("nodes")).size());
        assertEquals(firstData(before), firstData(result.document())); assertEquals(before, original);
        DiagramDocumentValidator.validate(result.document());
    }
    @Test void addsOnlyMissingAttributeAndKeepsIdsAndMetadata() {
        var original = base();
        firstData(original).put("attributes", List.of(m("id", "a", "name", "id", "type", "BIGINT", "primaryKey", true,
                "externalMetadata", m("source", "enterprise-architect", "externalId", "EAID_1"))));
        var result = ImageDiagramReconciler.reconcile(original, recognition(classifier("Cliente", "CLASS",
                List.of(m("name", "id", "type", "Long"), m("name", "correo", "type", "String")))));
        assertTrue(result.canApply()); assertEquals(1, result.additions().size());
        var attrs = (List<?>) firstData(result.document()).get("attributes");
        assertEquals(((List<?>) firstData(original).get("attributes")).getFirst(), attrs.getFirst());
        assertNotEquals("a", ((Map<?, ?>) attrs.get(1)).get("id"));
    }
    @Test void addsRelationshipAndRepeatedImageIsIdempotent() {
        var original = base();
        original.put("nodes", List.of(node("c", "Cliente", "CLASS"), node("p", "Pedido", "CLASS")));
        var seen = recognition(classifier("Cliente", "CLASS", List.of()), classifier("Pedido", "CLASS", List.of()), relation("Cliente", "Pedido"));
        var first = ImageDiagramReconciler.reconcile(original, seen);
        assertTrue(first.canApply()); assertEquals(1, ((List<?>) first.document().get("edges")).size());
        var repeated = ImageDiagramReconciler.reconcile(first.document(), seen);
        assertFalse(repeated.canApply()); assertTrue(repeated.additions().isEmpty()); assertTrue(repeated.conflicts().isEmpty());
        assertEquals(first.document(), repeated.document());
    }
    @Test void partialImageDoesNotRemoveAnyData() {
        var original = base();
        firstData(original).put("methods", List.of(m("id", "method-id", "name", "buscar", "returnType", "Cliente", "visibility", "public",
                "parameters", List.of(m("id", "param-id", "name", "id", "type", "Long")))));
        var result = ImageDiagramReconciler.reconcile(original, recognition(classifier("Cliente", "CLASS", List.of())));
        assertEquals(original, result.document()); assertFalse(result.canApply());
    }
    @ParameterizedTest @ValueSource(strings = {"DELETE_ENTITY", "UPDATE_ATTRIBUTE", "DELETE_ATTRIBUTE", "RENAME_ENTITY", "REPLACE_DOCUMENT", "UPDATE_RELATIONSHIP"})
    void rejectsNonadditiveOperationsInJava(String type) {
        assertThrows(DiagramValidationException.class, () -> ImageDiagramReconciler.reconcile(base(), recognition(m("type", type))));
    }
    @Test void ambiguousExistingNamesBlockWholeProposal() {
        var original = base();
        original.put("nodes", List.of(node("a", "Venta Item", "CLASS"), node("b", "Venta_Item", "CLASS")));
        var result = ImageDiagramReconciler.reconcile(original, recognition(classifier("VentaItem", "CLASS", List.of(m("name", "valor", "type", "DECIMAL"))),
                classifier("Nueva", "CLASS", List.of())));
        assertFalse(result.canApply()); assertFalse(result.conflicts().isEmpty()); assertEquals(original, result.document());
    }
    @Test void conflictingTypeCannotChangeExistingMember() {
        var original = base(); firstData(original).put("attributes", List.of(m("id", "a", "name", "id", "type", "BIGINT", "primaryKey", true)));
        var result = ImageDiagramReconciler.reconcile(original, recognition(classifier("Cliente", "CLASS", List.of(m("name", "id", "type", "String")))));
        assertFalse(result.canApply()); assertEquals(original, result.document()); assertFalse(result.conflicts().isEmpty());
    }
    @Test void addsMethodAndParametersWithoutChangingExistingMethod() {
        var original = base();
        var op = classifier("Cliente", "CLASS", List.of());
        op.put("methods", List.of(m("name", "buscar", "returnType", "Cliente", "parameters", List.of(m("name", "id", "type", "Long")))));
        var first = ImageDiagramReconciler.reconcile(original, recognition(op));
        assertTrue(first.canApply());
        var repeat = ImageDiagramReconciler.reconcile(first.document(), recognition(op));
        assertEquals(first.document(), repeat.document()); assertTrue(repeat.additions().isEmpty());
    }
    @ParameterizedTest @ValueSource(strings = {"CLASS", "INTERFACE", "ENUMERATION"})
    void preservesClassifierKinds(String kind) {
        var op = classifier("Nuevo", kind, List.of());
        if (kind.equals("ENUMERATION")) op.put("literals", List.of("ACTIVO", "INACTIVO"));
        var result = ImageDiagramReconciler.reconcile(m("version", 1, "nodes", List.of(), "edges", List.of()), recognition(op));
        assertTrue(result.canApply()); assertEquals(2, result.document().get("version")); assertEquals(kind, firstData(result.document()).get("kind"));
    }
    @Test void versionOneAttributeAdditionPreservesVersionAndDocumentFields() {
        var original = base(); original.put("version", 1); firstData(original).remove("kind");
        var result = ImageDiagramReconciler.reconcile(original, recognition(classifier("Cliente", "ENTITY", List.of(m("name", "email", "type", "VARCHAR")))));
        assertTrue(result.canApply()); assertEquals(1, result.document().get("version")); assertFalse(firstData(result.document()).containsKey("kind"));
    }
    @Test void unsupportedNewAssociationClassIsNotDegraded() {
        var result = ImageDiagramReconciler.reconcile(base(), recognition(classifier("Inscripcion", "ASSOCIATION_CLASS", List.of())));
        assertFalse(result.canApply()); assertFalse(result.warnings().isEmpty()); assertEquals(base(), result.document());
    }
    @Test void existingAssociationClassRemainsAssociationClass() {
        var original = base(); firstData(original).put("kind", "ASSOCIATION_CLASS");
        var result = ImageDiagramReconciler.reconcile(original, recognition(classifier("Cliente", "ASSOCIATION_CLASS", List.of(m("name", "extra", "type", "String")))));
        assertTrue(result.canApply()); assertEquals("ASSOCIATION_CLASS", firstData(result.document()).get("kind"));
    }
    @Test void multiplicityConflictPreservesExistingEdge() {
        var original = base(); original.put("nodes", List.of(node("c", "Cliente", "CLASS"), node("p", "Pedido", "CLASS")));
        var first = ImageDiagramReconciler.reconcile(original, recognition(relation("Cliente", "Pedido")));
        var changed = relation("Cliente", "Pedido"); changed.put("targetMultiplicity", "1");
        var result = ImageDiagramReconciler.reconcile(first.document(), recognition(changed));
        assertFalse(result.canApply()); assertFalse(result.conflicts().isEmpty()); assertEquals(first.document(), result.document());
    }
    @Test void rejectsInjectedIdsAndUnknownFields() {
        var op = classifier("New", "CLASS", List.of()); op.put("id", "c");
        assertThrows(DiagramValidationException.class, () -> ImageDiagramReconciler.reconcile(base(), recognition(op)));
    }

    @ParameterizedTest @ValueSource(strings = {"ASSOCIATION", "GENERALIZATION", "REALIZATION", "AGGREGATION", "COMPOSITION", "DEPENDENCY"})
    void supportsAllExistingUmlRelationshipKindsAndPreservesThem(String kind) {
        var original = base();
        original.put("nodes", List.of(node("c", "Cliente", "CLASS"), node("p", "Pedido", kind.equals("REALIZATION") ? "INTERFACE" : "CLASS")));
        var op = relation("Cliente", "Pedido"); op.put("relationKind", kind);
        if (Set.of("GENERALIZATION", "REALIZATION", "DEPENDENCY").contains(kind)) {
            op.remove("sourceMultiplicity"); op.remove("targetMultiplicity");
        }
        if (Set.of("AGGREGATION", "COMPOSITION").contains(kind)) op.put("aggregationEnd", "TARGET");
        var first = ImageDiagramReconciler.reconcile(original, recognition(op));
        assertTrue(first.canApply(), first.conflicts().toString());
        var existingEdges = ImageDiagramReconciler.copy(first.document().get("edges"));
        var second = ImageDiagramReconciler.reconcile(first.document(), recognition(op, classifier("Cliente", "CLASS", List.of(m("name", "nuevo", "type", "String")))));
        assertTrue(second.canApply(), second.conflicts().toString());
        assertEquals(existingEdges, second.document().get("edges"));
    }
    @Test void preservesAssociationMetadataStructuralEdgesAndVisualFields() {
        var original = base();
        var association = node("ac", "Inscripcion", "ASSOCIATION_CLASS");
        @SuppressWarnings("unchecked") var data = (Map<String, Object>) association.get("data");
        data.put("association", m("kind", "MANY_TO_MANY_ASSOCIATION", "tableName", "inscripcion", "uniquePair", true,
                "endpoints", List.of(m("role", "SOURCE", "entityId", "c", "relationshipId", "e1", "foreignKeyName", "cliente_id"),
                        m("role", "TARGET", "entityId", "p", "relationshipId", "e2", "foreignKeyName", "pedido_id"))));
        var e1 = m("id", "e1", "source", "c", "target", "ac", "sourceHandle", "right", "data",
                m("sourceCardinality", "ONE_ONE", "targetCardinality", "ZERO_MANY", "customVisual", m("color", "blue")));
        var e2 = m("id", "e2", "source", "p", "target", "ac", "data", m());
        original.put("nodes", List.of(node("c", "Cliente", "CLASS"), node("p", "Pedido", "CLASS"), association));
        original.put("edges", List.of(e1, e2));
        DiagramDocumentValidator.validate(original);
        var result = ImageDiagramReconciler.reconcile(original, recognition(classifier("Inscripcion", "ASSOCIATION_CLASS", List.of(m("name", "nota", "type", "String")))));
        assertTrue(result.canApply(), result.conflicts().toString());
        assertEquals(original.get("edges"), result.document().get("edges"));
        var updated = (Map<?, ?>) ((List<?>) result.document().get("nodes")).get(2);
        assertEquals(association.get("position"), updated.get("position"));
        assertEquals(data.get("association"), ((Map<?, ?>) updated.get("data")).get("association"));
    }
    @Test void floatingPointIsNotSilentlyConvertedToDecimal() {
        var result = ImageDiagramReconciler.reconcile(base(), recognition(classifier("Cliente", "CLASS", List.of(m("name", "balance", "type", "Double")))));
        assertFalse(result.canApply()); assertFalse(result.warnings().isEmpty()); assertEquals(base(), result.document());
    }
    @Test void reverseAssociationAndEquivalentMultiplicityAreNotDuplicated() {
        var original = base(); original.put("nodes", List.of(node("c", "Cliente", "CLASS"), node("p", "Pedido", "CLASS")));
        var first = ImageDiagramReconciler.reconcile(original, recognition(relation("Cliente", "Pedido")));
        var reverse = relation("Pedido", "Cliente"); reverse.put("sourceMultiplicity", "*"); reverse.put("targetMultiplicity", "1..1");
        var second = ImageDiagramReconciler.reconcile(first.document(), recognition(reverse));
        assertFalse(second.canApply()); assertTrue(second.conflicts().isEmpty()); assertEquals(first.document(), second.document());
    }
    @Test void reversingDirectedRelationshipIsConflictNotAnotherConnector() {
        var original = base(); original.put("nodes", List.of(node("c", "Cliente", "CLASS"), node("p", "Persona", "CLASS")));
        var first = ImageDiagramReconciler.reconcile(original, recognition(m("type", "ADD_RELATIONSHIP", "source", "Cliente", "target", "Persona", "relationKind", "GENERALIZATION")));
        var second = ImageDiagramReconciler.reconcile(first.document(), recognition(m("type", "ADD_RELATIONSHIP", "source", "Persona", "target", "Cliente", "relationKind", "GENERALIZATION")));
        assertFalse(second.canApply()); assertFalse(second.conflicts().isEmpty()); assertEquals(first.document(), second.document());
    }
    @Test void associationClassEndpointRelationshipMatchesWithoutDuplicateEdgeAndAddsNewElements() {
        var original = m("version", 2, "nodes", new ArrayList<>(), "edges", new ArrayList<>());
        var producto = node("prod", "Producto", "CLASS");
        ((Map<String, Object>) producto.get("data")).put("attributes", List.of(
                m("id", "a1", "name", "ID", "type", "INTEGER", "primaryKey", true, "nullable", false),
                m("id", "a2", "name", "Nombre", "type", "VARCHAR", "primaryKey", false, "nullable", true)));
        var venta = node("ven", "Venta", "CLASS");
        ((Map<String, Object>) venta.get("data")).put("attributes", List.of(
                m("id", "a3", "name", "ID", "type", "INTEGER", "primaryKey", true, "nullable", false)));
        var auditoria = node("aud", "Auditoria", "ENTITY");
        var compra = node("comp", "compra", "ASSOCIATION_CLASS");
        ((Map<String, Object>) compra.get("data")).put("attributes", List.of(
                m("id", "a4", "name", "id", "type", "INTEGER", "primaryKey", true, "nullable", false)));
        ((Map<String, Object>) compra.get("data")).put("association", m(
                "kind", "MANY_TO_MANY_ASSOCIATION", "tableName", "venta_producto", "uniquePair", true,
                "sourceMultiplicity", "ONE_MANY", "targetMultiplicity", "ONE_MANY",
                "endpoints", List.of(
                        m("role", "SOURCE", "entityId", "ven", "relationshipId", "e1", "foreignKeyName", "venta_id"),
                        m("role", "TARGET", "entityId", "prod", "relationshipId", "e2", "foreignKeyName", "producto_id"))));
        var e1 = m("id", "e1", "source", "ven", "target", "comp", "data",
                m("relationKind", "ASSOCIATION", "sourceCardinality", "ONE_ONE", "targetCardinality", "ONE_MANY",
                        "sourceMultiplicity", "1", "targetMultiplicity", "1..*"));
        var e2 = m("id", "e2", "source", "prod", "target", "comp", "data",
                m("relationKind", "ASSOCIATION", "sourceCardinality", "ONE_ONE", "targetCardinality", "ONE_MANY",
                        "sourceMultiplicity", "1", "targetMultiplicity", "1..*"));
        original.put("nodes", List.of(producto, venta, compra, auditoria));
        original.put("edges", List.of(e1, e2));

        var prodOp = classifier("Producto", "CLASS", List.of(
                m("name", "ID", "type", "INTEGER", "primaryKey", true, "visibility", "private"),
                m("name", "Nombre", "type", "VARCHAR"),
                m("name", "Stock", "type", "INTEGER")));
        var ventaOp = m("type", "ADD_CLASSIFIER", "name", "Venta",
                "attributes", List.of(m("name", "ID", "type", "INTEGER", "primaryKey", true)),
                "methods", List.of(), "literals", List.of());
        var compraOp = classifier("compra", "ASSOCIATION_CLASS", List.of(
                m("name", "id", "type", "INTEGER", "primaryKey", true),
                m("name", "cantidad", "type", "INTEGER")));
        var categoriaOp = classifier("Categoria", "CLASS", List.of(
                m("name", "id", "type", "INTEGER", "primaryKey", true, "nullable", true)));
        var assocRel = m("type", "ADD_RELATIONSHIP", "source", "Venta", "target", "Producto",
                "relationKind", "ASSOCIATION", "sourceMultiplicity", "1..*", "targetMultiplicity", "1..*");
        var catRel = m("type", "ADD_RELATIONSHIP", "source", "Producto", "target", "Categoria",
                "relationKind", "ASSOCIATION", "sourceMultiplicity", "0..*", "targetMultiplicity", "1");

        var result = ImageDiagramReconciler.reconcile(original, recognition(prodOp, ventaOp, compraOp, categoriaOp, assocRel, catRel));
        assertTrue(result.canApply(), () -> "Conflicts: " + result.conflicts());
        assertTrue(result.conflicts().isEmpty());
        var nodes = (List<?>) result.document().get("nodes");
        var edges = (List<?>) result.document().get("edges");
        assertEquals(5, nodes.size());
        assertEquals(3, edges.size());
        DiagramDocumentValidator.validate(result.document());
    }
    @Test void associationClassIncompatibleMultiplicityProducesControlledConflict() {
        var original = m("version", 2, "nodes", new ArrayList<>(), "edges", new ArrayList<>());
        var producto = node("prod", "Producto", "CLASS");
        var venta = node("ven", "Venta", "CLASS");
        var compra = node("comp", "compra", "ASSOCIATION_CLASS");
        ((Map<String, Object>) compra.get("data")).put("association", m(
                "kind", "MANY_TO_MANY_ASSOCIATION", "tableName", "venta_producto", "uniquePair", true,
                "sourceMultiplicity", "ONE_MANY", "targetMultiplicity", "ONE_MANY",
                "endpoints", List.of(
                        m("role", "SOURCE", "entityId", "ven", "relationshipId", "e1", "foreignKeyName", "venta_id"),
                        m("role", "TARGET", "entityId", "prod", "relationshipId", "e2", "foreignKeyName", "producto_id"))));
        var e1 = m("id", "e1", "source", "ven", "target", "comp", "data",
                m("relationKind", "ASSOCIATION", "sourceCardinality", "ONE_ONE", "targetCardinality", "ONE_MANY"));
        var e2 = m("id", "e2", "source", "prod", "target", "comp", "data",
                m("relationKind", "ASSOCIATION", "sourceCardinality", "ONE_ONE", "targetCardinality", "ONE_MANY"));
        original.put("nodes", List.of(producto, venta, compra));
        original.put("edges", List.of(e1, e2));

        var badRel = m("type", "ADD_RELATIONSHIP", "source", "Venta", "target", "Producto",
                "relationKind", "ASSOCIATION", "sourceMultiplicity", "1", "targetMultiplicity", "1");
        var result = ImageDiagramReconciler.reconcile(original, recognition(badRel));
        assertFalse(result.canApply());
        assertFalse(result.conflicts().isEmpty());
        assertEquals(original, result.document());
    }

    static Map<String, Object> ventaProductoCompraDocument() {
        var original = m("version", 2, "nodes", new ArrayList<>(), "edges", new ArrayList<>());
        var venta = node("ven", "Venta", "CLASS");
        ((Map<String, Object>) venta.get("data")).put("attributes", List.of(
                m("id", "v1", "name", "id", "type", "INTEGER", "primaryKey", true, "nullable", false)));
        var producto = node("prod", "Producto", "CLASS");
        ((Map<String, Object>) producto.get("data")).put("attributes", List.of(
                m("id", "p1", "name", "id", "type", "INTEGER", "primaryKey", true, "nullable", false)));
        var compra = node("comp", "compra", "ASSOCIATION_CLASS");
        ((Map<String, Object>) compra.get("data")).put("attributes", List.of(
                m("id", "c1", "name", "id", "type", "INTEGER", "primaryKey", true, "nullable", false)));
        ((Map<String, Object>) compra.get("data")).put("association", m(
                "kind", "MANY_TO_MANY_ASSOCIATION", "tableName", "venta_producto", "uniquePair", true,
                "sourceMultiplicity", "ZERO_MANY", "targetMultiplicity", "ZERO_MANY",
                "endpoints", List.of(
                        m("role", "SOURCE", "entityId", "ven", "relationshipId", "e1", "foreignKeyName", "venta_id"),
                        m("role", "TARGET", "entityId", "prod", "relationshipId", "e2", "foreignKeyName", "producto_id"))));
        var e1 = m("id", "e1", "source", "ven", "target", "comp", "data",
                m("relationKind", "ASSOCIATION", "sourceCardinality", "ONE_ONE", "targetCardinality", "ZERO_MANY",
                        "sourceMultiplicity", "1", "targetMultiplicity", "0..*"));
        var e2 = m("id", "e2", "source", "prod", "target", "comp", "data",
                m("relationKind", "ASSOCIATION", "sourceCardinality", "ONE_ONE", "targetCardinality", "ZERO_MANY",
                        "sourceMultiplicity", "1", "targetMultiplicity", "0..*"));
        original.put("nodes", List.of(venta, producto, compra));
        original.put("edges", List.of(e1, e2));
        DiagramDocumentValidator.validate(original);
        return original;
    }

    @Test void createsNewAssociationClassBetweenNewEntityAndExistingAssociationClassWithZeroToNAndDashedLine() {
        var original = ventaProductoCompraDocument();
        var ventaOp = classifier("Venta", "CLASS", List.of(m("name", "id", "type", "INTEGER", "primaryKey", true)));
        var prodOp = classifier("Producto", "CLASS", List.of(m("name", "id", "type", "INTEGER", "primaryKey", true)));
        var compraOp = classifier("compra", "ASSOCIATION_CLASS", List.of(
                m("name", "id", "type", "INTEGER", "primaryKey", true),
                m("name", "venta_id", "type", "INTEGER"),
                m("name", "producto_id", "type", "INTEGER")));
        var devolucionOp = classifier("Devolucion", "ENTITY", List.of(
                m("name", "id", "type", "INTEGER", "primaryKey", true),
                m("name", "motivo", "type", "VARCHAR")));
        var devCompraOp = classifier("devolucion_compra", "ASSOCIATION_CLASS", List.of(
                m("name", "id", "type", "INTEGER", "primaryKey", true),
                m("name", "devolucion_id", "type", "INTEGER"),
                m("name", "compra_id", "type", "INTEGER"),
                m("name", "cantidad", "type", "INTEGER")));
        var existingRel = m("type", "ADD_RELATIONSHIP", "source", "Venta", "target", "Producto",
                "relationKind", "ASSOCIATION", "sourceMultiplicity", "0..N", "targetMultiplicity", "0..N");
        var existingDashed = m("type", "ADD_RELATIONSHIP", "source", "compra", "target", "Venta",
                "relationKind", "DEPENDENCY");
        // Even if Gemini omitted one multiplicity on Devolucion -> compra and put 0..N on the dashed connector devolucion_compra -> Devolucion:
        var newRel = m("type", "ADD_RELATIONSHIP", "source", "Devolucion", "target", "compra",
                "relationKind", "ASSOCIATION", "targetMultiplicity", "0..N");
        var newDashed = m("type", "ADD_RELATIONSHIP", "source", "devolucion_compra", "target", "Devolucion",
                "relationKind", "ASSOCIATION", "sourceMultiplicity", "0..N");

        var seen = recognition(ventaOp, prodOp, compraOp, devolucionOp, devCompraOp, existingRel, existingDashed, newRel, newDashed);
        var first = ImageDiagramReconciler.reconcile(original, seen);
        assertTrue(first.canApply(), () -> "Conflicts: " + first.conflicts() + " Warnings: " + first.warnings());
        assertTrue(first.conflicts().isEmpty());
        DiagramDocumentValidator.validate(first.document());

        var nodes = (List<?>) first.document().get("nodes");
        var edges = (List<?>) first.document().get("edges");
        assertEquals(5, nodes.size());
        assertEquals(4, edges.size());

        @SuppressWarnings("unchecked")
        var devCompraNode = (Map<String, Object>) nodes.stream()
                .map(n -> (Map<String, Object>) n)
                .filter(n -> "devolucion_compra".equals(((Map<?, ?>) n.get("data")).get("name")))
                .findFirst().orElseThrow();
        @SuppressWarnings("unchecked")
        var devCompraData = (Map<String, Object>) devCompraNode.get("data");
        assertEquals("ASSOCIATION_CLASS", devCompraData.get("kind"));
        @SuppressWarnings("unchecked")
        var assocMeta = (Map<String, Object>) devCompraData.get("association");
        assertNotNull(assocMeta);
        assertEquals("ZERO_MANY", assocMeta.get("sourceMultiplicity"));
        assertEquals("ZERO_MANY", assocMeta.get("targetMultiplicity"));
        // Structural foreign keys devolucion_id and compra_id must not duplicate into own attributes
        @SuppressWarnings("unchecked")
        var attrs = (List<Map<String, Object>>) devCompraData.get("attributes");
        assertEquals(2, attrs.size());
        assertTrue(attrs.stream().anyMatch(a -> "id".equals(a.get("name"))));
        assertTrue(attrs.stream().anyMatch(a -> "cantidad".equals(a.get("name"))));

        // Repeated image is idempotent and produces 0 additions and 0 conflicts
        var repeated = ImageDiagramReconciler.reconcile(first.document(), seen);
        assertFalse(repeated.canApply());
        assertTrue(repeated.additions().isEmpty(), () -> "Unexpected additions: " + repeated.additions());
        assertTrue(repeated.conflicts().isEmpty(), () -> "Unexpected conflicts: " + repeated.conflicts());
        assertEquals(first.document(), repeated.document());
    }

    @Test void supportsSimpleAssociationBetweenEntityAndExistingAssociationClassWithZeroToNAndOneToN() {
        var original = ventaProductoCompraDocument();
        var devolucionOp = classifier("Devolucion", "ENTITY", List.of(
                m("name", "id", "type", "INTEGER", "primaryKey", true)));
        var rel = m("type", "ADD_RELATIONSHIP", "source", "Devolucion", "target", "compra",
                "relationKind", "ASSOCIATION", "sourceMultiplicity", "0..N", "targetMultiplicity", "1..N");
        var first = ImageDiagramReconciler.reconcile(original, recognition(devolucionOp, rel));
        assertTrue(first.canApply(), () -> "Conflicts: " + first.conflicts());
        assertTrue(first.conflicts().isEmpty());
        assertEquals(4, ((List<?>) first.document().get("nodes")).size());
        assertEquals(3, ((List<?>) first.document().get("edges")).size());
        DiagramDocumentValidator.validate(first.document());

        var repeated = ImageDiagramReconciler.reconcile(first.document(), recognition(devolucionOp, rel));
        assertFalse(repeated.canApply());
        assertTrue(repeated.additions().isEmpty());
        assertTrue(repeated.conflicts().isEmpty());
    }

    @Test void createsNewAssociationClassFromTwoExplicitConnectorLegsWithOneToNAndZeroToN() {
        var original = m("version", 2, "nodes", List.of(
                node("al", "Alumno", "CLASS"),
                node("mat", "Materia", "CLASS")), "edges", List.of());
        var inscOp = classifier("Inscripcion", "ASSOCIATION_CLASS", List.of(
                m("name", "nota", "type", "INTEGER")));
        var leg1 = m("type", "ADD_RELATIONSHIP", "source", "Alumno", "target", "Inscripcion",
                "relationKind", "ASSOCIATION", "sourceMultiplicity", "1", "targetMultiplicity", "0..N");
        var leg2 = m("type", "ADD_RELATIONSHIP", "source", "Materia", "target", "Inscripcion",
                "relationKind", "ASSOCIATION", "sourceMultiplicity", "1", "targetMultiplicity", "1..N");

        var result = ImageDiagramReconciler.reconcile(original, recognition(inscOp, leg1, leg2));
        assertTrue(result.canApply(), () -> "Conflicts: " + result.conflicts());
        assertTrue(result.conflicts().isEmpty());
        assertEquals(3, ((List<?>) result.document().get("nodes")).size());
        assertEquals(2, ((List<?>) result.document().get("edges")).size());
        DiagramDocumentValidator.validate(result.document());
    }

    @Test void blocksAmbiguousNewAssociationClassWhenMultipleUnrelatedAssociationsExist() {
        var original = m("version", 2, "nodes", List.of(
                node("a", "Alumno", "CLASS"),
                node("m", "Materia", "CLASS"),
                node("p", "Profesor", "CLASS"),
                node("c", "Curso", "CLASS")), "edges", List.of());
        var assocOp = classifier("Registro", "ASSOCIATION_CLASS", List.of());
        var rel1 = m("type", "ADD_RELATIONSHIP", "source", "Alumno", "target", "Materia",
                "relationKind", "ASSOCIATION", "sourceMultiplicity", "0..N", "targetMultiplicity", "0..N");
        var rel2 = m("type", "ADD_RELATIONSHIP", "source", "Profesor", "target", "Curso",
                "relationKind", "ASSOCIATION", "sourceMultiplicity", "1..N", "targetMultiplicity", "0..N");

        var result = ImageDiagramReconciler.reconcile(original, recognition(assocOp, rel1, rel2));
        assertFalse(result.canApply());
        assertTrue(result.conflicts().stream().anyMatch(c -> c.contains("Registro")));
        assertEquals(original, result.document());
    }

    @Test void blocksNewAssociationClassWhenMultiplicitiesAreGenuinelyMissingWithoutInventingValues() {
        var original = ventaProductoCompraDocument();
        var devolucionOp = classifier("Devolucion", "ENTITY", List.of(
                m("name", "id", "type", "INTEGER", "primaryKey", true)));
        var devCompraOp = classifier("devolucion_compra", "ASSOCIATION_CLASS", List.of());
        var newRelWithoutMultiplicity = m("type", "ADD_RELATIONSHIP", "source", "Devolucion", "target", "compra",
                "relationKind", "ASSOCIATION");
        var dashedWithoutMultiplicity = m("type", "ADD_RELATIONSHIP", "source", "devolucion_compra", "target", "Devolucion",
                "relationKind", "ASSOCIATION");

        var result = ImageDiagramReconciler.reconcile(original,
                recognition(devolucionOp, devCompraOp, newRelWithoutMultiplicity, dashedWithoutMultiplicity));
        assertFalse(result.canApply());
        assertEquals(1, result.conflicts().size(), () -> "Conflicts: " + result.conflicts());
        assertTrue(result.conflicts().getFirst().contains("Multiplicidad ausente o no soportada: Devolucion → compra"));
        assertEquals(original, result.document());
    }

    @Test void recognizesReadOnlyDerivedJoinTableOnExistingManyToManyEdgeWithoutFalseConflict() {
        var original = m("version", 2, "nodes", List.of(
                node("v", "Venta", "CLASS"),
                node("p", "Producto", "CLASS")), "edges", List.of(
                m("id", "e1", "source", "v", "target", "p", "data",
                        m("relationKind", "ASSOCIATION", "sourceCardinality", "ZERO_MANY", "targetCardinality", "ZERO_MANY",
                                "sourceMultiplicity", "0..*", "targetMultiplicity", "0..*"))));
        var derivedOp = classifier("venta_producto", "ASSOCIATION_CLASS", List.of(
                m("name", "id", "type", "INTEGER", "primaryKey", true),
                m("name", "venta_id", "type", "INTEGER"),
                m("name", "producto_id", "type", "INTEGER")));
        var dashedOp = m("type", "ADD_RELATIONSHIP", "source", "venta_producto", "target", "Venta",
                "relationKind", "ASSOCIATION");
        var newClassOp = classifier("Devolucion", "ENTITY", List.of(
                m("name", "id", "type", "INTEGER", "primaryKey", true)));

        var result = ImageDiagramReconciler.reconcile(original, recognition(derivedOp, dashedOp, newClassOp));
        assertTrue(result.canApply(), () -> "Conflicts: " + result.conflicts());
        assertTrue(result.conflicts().isEmpty());
        assertEquals(3, ((List<?>) result.document().get("nodes")).size());
    }

    @Test void reconcilesNewClassAndAssociationClassWithCompleteAttributesPkFkPrefixesMethodsAndLiterals() {
        var original = ventaProductoCompraDocument();
        var ventaOp = classifier("Venta", "CLASS", List.of(
                m("name", "PK id", "type", "INTEGER"),
                m("name", "· fecha", "type", "LocalDate"),
                m("name", "+ Cantidad de unidades vendidas", "type", "INT"),
                m("name", "Sin atributos", "type", "VARCHAR")));
        ventaOp.put("methods", List.of(
                m("name", "+ calcularTotal()", "returnType", "DECIMAL",
                        "parameters", List.of(m("name", "descuento", "type", "DECIMAL")))));

        var prodOp = classifier("Producto", "CLASS", List.of(
                m("name", "id", "type", "INTEGER"),
                m("name", "Nombre", "type", "VARCHAR(120)"),
                m("name", "- Precio de venta", "type", "DECIMAL(10,2)")));

        var compraOp = classifier("compra", "ASSOCIATION_CLASS", List.of(
                m("name", "PK id", "type", "INTEGER"),
                m("name", "FK venta_id", "type", "INTEGER"),
                m("name", "FK producto_id", "type", "INTEGER"),
                m("name", "· Fecha de compra", "type", "DATE")));

        var devolucionOp = classifier("Devolucion", "ENTITY", List.of(
                m("name", "PK id", "type", "INTEGER"),
                m("name", "· Fecha", "type", "DATE"),
                m("name", "· Motivo", "type", "VARCHAR(255)"),
                m("name", "· Monto", "type", "NUMERIC(10,2)")));

        var devCompraOp = classifier("devolucion_compra", "ASSOCIATION_CLASS", List.of(
                m("name", "PK id", "type", "INTEGER"),
                m("name", "FK devolucion_id", "type", "INTEGER"),
                m("name", "FK compra_id", "type", "INTEGER"),
                m("name", "+ cantidad_devuelta", "type", "INTEGER"),
                m("name", "- observacion", "type", "TEXT")));

        var AuditableOp = classifier("Auditable", "INTERFACE", List.of());
        AuditableOp.put("methods", List.of(
                m("name", "registrarEvento", "returnType", " void ".strip(), "visibility", "public",
                        "parameters", List.of(m("name", "usuario", "type", "String")))));

        var estadoOp = classifier("EstadoDevolucion", "ENUMERATION", List.of());
        estadoOp.put("literals", List.of("· PENDIENTE", "APROBADA", "RECHAZADA", "Sin literales"));

        var relExisting = m("type", "ADD_RELATIONSHIP", "source", "Venta", "target", "Producto",
                "relationKind", "ASSOCIATION", "sourceMultiplicity", "0..N", "targetMultiplicity", "0..N");
        var relNewAssoc = m("type", "ADD_RELATIONSHIP", "source", "Devolucion", "target", "compra",
                "relationKind", "ASSOCIATION", "sourceMultiplicity", "0..N", "targetMultiplicity", "0..N");
        var relRealization = m("type", "ADD_RELATIONSHIP", "source", "Venta", "target", "Auditable",
                "relationKind", "REALIZATION");
        var relDependency = m("type", "ADD_RELATIONSHIP", "source", "Devolucion", "target", "EstadoDevolucion",
                "relationKind", "DEPENDENCY");

        var seen = recognition(
                ventaOp, prodOp, compraOp, devolucionOp, devCompraOp,
                AuditableOp, estadoOp, relExisting, relNewAssoc, relRealization, relDependency);
        var first = ImageDiagramReconciler.reconcile(original, seen);
        assertTrue(first.canApply(), () -> "Conflicts: " + first.conflicts());
        assertTrue(first.conflicts().isEmpty());
        DiagramDocumentValidator.validate(first.document());

        @SuppressWarnings("unchecked")
        var nodes = (List<Map<String, Object>>) first.document().get("nodes");
        assertEquals(7, nodes.size());

        var devolucionNode = nodes.stream()
                .filter(n -> "Devolucion".equals(((Map<?, ?>) n.get("data")).get("name")))
                .findFirst().orElseThrow();
        @SuppressWarnings("unchecked")
        var devAttrs = (List<Map<String, Object>>) ((Map<String, Object>) devolucionNode.get("data")).get("attributes");
        assertEquals(4, devAttrs.size());
        assertEquals("id", devAttrs.get(0).get("name"));
        assertEquals(Boolean.TRUE, devAttrs.get(0).get("primaryKey"));
        assertEquals("Fecha", devAttrs.get(1).get("name"));
        assertEquals("DATE", devAttrs.get(1).get("type"));
        assertEquals("Motivo", devAttrs.get(2).get("name"));
        assertEquals("VARCHAR", devAttrs.get(2).get("type"));
        assertEquals("Monto", devAttrs.get(3).get("name"));
        assertEquals("DECIMAL", devAttrs.get(3).get("type"));

        var devCompraNode = nodes.stream()
                .filter(n -> "devolucion_compra".equals(((Map<?, ?>) n.get("data")).get("name")))
                .findFirst().orElseThrow();
        @SuppressWarnings("unchecked")
        var dcAttrs = (List<Map<String, Object>>) ((Map<String, Object>) devCompraNode.get("data")).get("attributes");
        assertEquals(3, dcAttrs.size());
        assertTrue(dcAttrs.stream().anyMatch(a -> "id".equals(a.get("name")) && Boolean.TRUE.equals(a.get("primaryKey"))));
        assertTrue(dcAttrs.stream().anyMatch(a -> "cantidad_devuelta".equals(a.get("name")) && "public".equals(a.get("visibility"))));
        assertTrue(dcAttrs.stream().anyMatch(a -> "observacion".equals(a.get("name")) && "private".equals(a.get("visibility"))));

        var estadoNode = nodes.stream()
                .filter(n -> "EstadoDevolucion".equals(((Map<?, ?>) n.get("data")).get("name")))
                .findFirst().orElseThrow();
        assertEquals(List.of("PENDIENTE", "APROBADA", "RECHAZADA"),
                ((Map<?, ?>) estadoNode.get("data")).get("literals"));

        // Re-running with the exact same image recognition is 100% idempotent
        var repeated = ImageDiagramReconciler.reconcile(first.document(), seen);
        assertFalse(repeated.canApply());
        assertTrue(repeated.additions().isEmpty(), () -> "Unexpected additions: " + repeated.additions());
        assertTrue(repeated.conflicts().isEmpty(), () -> "Unexpected conflicts: " + repeated.conflicts());
        assertEquals(first.document(), repeated.document());
    }
}
