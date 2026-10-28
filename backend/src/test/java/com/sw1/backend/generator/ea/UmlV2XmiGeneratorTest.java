package com.sw1.backend.generator.ea;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;

class UmlV2XmiGeneratorTest {
    @Test
    void exportsUmlClassifiersFeaturesAndRelationships() {
        Map<String, Object> entity = node("customer", "Cliente", "ENTITY");
        data(entity).put("attributes", List.of(Map.of(
                "id", "customer-id", "name", "id", "type", "Long", "visibility", "private",
                "primaryKey", true, "static", false)));
        data(entity).put("methods", List.of(Map.of(
                "id", "find", "name", "buscar", "returnType", "Cliente", "visibility", "public",
                "static", true, "abstract", false,
                "parameters", List.of(Map.of("id", "code", "name", "codigo", "type", "String")))));
        Map<String, Object> contract = node("contract", "Consultable", "INTERFACE");
        Map<String, Object> enumeration = node("state", "Estado", "ENUMERATION");
        data(enumeration).put("literals", List.of("ACTIVO", "INACTIVO"));
        Map<String, Object> child = node("vip", "ClienteVip", "CLASS");

        Map<String, Object> document = new LinkedHashMap<>();
        document.put("version", 2);
        document.put("nodes", List.of(entity, contract, enumeration, child));
        document.put("edges", List.of(
                edge("inherits", "vip", "customer", "GENERALIZATION"),
                edge("implements", "customer", "contract", "REALIZATION"),
                edge("owns", "customer", "state", "COMPOSITION")));

        String xmi = new String(UmlV2XmiGenerator.generate("Ventas", document), StandardCharsets.UTF_8);

        assertTrue(xmi.contains("xmi:type=\"uml:Interface\""));
        assertTrue(xmi.contains("xmi:type=\"uml:Enumeration\""));
        assertTrue(xmi.contains("ownedOperation"));
        assertTrue(xmi.contains("ownedParameter"));
        assertTrue(xmi.contains("xmi:type=\"uml:Generalization\""));
        assertTrue(xmi.contains("xmi:type=\"uml:InterfaceRealization\""));
        assertTrue(xmi.contains("aggregation=\"composite\""));
        assertTrue(xmi.contains("memberEnd"));
        assertTrue(xmi.contains("xmi:Extension"));
        assertTrue(xmi.contains("<diagrams>"));
        assertTrue(xmi.contains("geometry=\"Left="));
        assertTrue(xmi.contains("subject=\""));
    }

    @Test
    void rejectsRelationshipToMissingNodeWithSpecificMessage() {
        Map<String, Object> document = Map.of(
                "version", 2,
                "nodes", List.of(node("customer", "Cliente", "ENTITY")),
                "edges", List.of(edge("broken", "customer", "missing", "ASSOCIATION")));

        EnterpriseArchitectXmiException error = assertThrows(EnterpriseArchitectXmiException.class,
                () -> UmlV2XmiGenerator.generate("Ventas", document));
        assertTrue(error.getMessage().contains("nodo inexistente"));
    }

    @Test
    void eaDiagramOwnsEveryVisualElementAndConnectorReference() throws Exception {
        Map<String, Object> first = node("first", "Primera", "CLASS");
        Map<String, Object> second = node("second", "Segunda", "CLASS");
        first.put("position", Map.of("x", 40, "y", 80));
        second.put("position", Map.of("x", 440, "y", 280));
        Map<String, Object> document = Map.of("version", 2, "nodes", List.of(first, second),
                "edges", List.of(edge("association", "first", "second", "ASSOCIATION")));
        byte[] bytes = UmlV2XmiGenerator.generate("Visual", document);
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        var xml = factory.newDocumentBuilder().parse(new ByteArrayInputStream(bytes));

        var ids = new HashSet<String>();
        var all = xml.getElementsByTagName("*");
        for (int index = 0; index < all.getLength(); index++) {
            var element = (org.w3c.dom.Element) all.item(index);
            String id = element.getAttributeNS("http://schema.omg.org/spec/XMI/2.1", "id");
            if (!id.isBlank()) ids.add(id);
        }
        var diagrams = xml.getElementsByTagName("diagram");
        assertTrue(diagrams.getLength() == 1);
        var diagram = (org.w3c.dom.Element) diagrams.item(0);
        var model = (org.w3c.dom.Element) diagram.getElementsByTagName("model").item(0);
        assertTrue(model.getAttribute("package").startsWith("EAPK_"));
        assertTrue(ids.contains(model.getAttribute("package")));
        assertTrue(ids.contains(model.getAttribute("owner")));
        var visualElements = ((org.w3c.dom.Element) diagram.getElementsByTagName("elements").item(0))
                .getElementsByTagName("element");
        assertTrue(visualElements.getLength() == 3);
        for (int index = 0; index < visualElements.getLength(); index++) {
            var visual = (org.w3c.dom.Element) visualElements.item(index);
            assertTrue(ids.contains(visual.getAttribute("subject")), visual.getAttribute("subject"));
            assertTrue(!visual.getAttribute("geometry").isBlank());
        }
    }

    @Test
    void exportsAssociationClassWithEaLinkageAndRoundTripsWithoutDuplicateRelations() throws Exception {
        Map<String, Object> product = node("product", "Producto", "ENTITY");
        Map<String, Object> sale = node("sale", "Venta", "ENTITY");
        Map<String, Object> line = node("line", "ProductoVenta", "ASSOCIATION_CLASS");
        data(line).put("attributes", List.of(Map.of(
                "id", "quantity", "name", "cantidad", "type", "INTEGER",
                "primaryKey", false, "nullable", false, "visibility", "private", "static", false)));
        Map<String, Object> association = new LinkedHashMap<>();
        association.put("kind", "MANY_TO_MANY_ASSOCIATION");
        association.put("tableName", "producto_venta");
        association.put("uniquePair", true);
        association.put("sourceMultiplicity", "ONE_MANY");
        association.put("targetMultiplicity", "ZERO_MANY");
        association.put("externalMetadata", Map.of(
                "source", "enterprise-architect", "externalId", "EAID_connector_product_sale"));
        association.put("endpoints", List.of(
                Map.of("role", "SOURCE", "entityId", "product", "relationshipId", "product-line",
                        "foreignKeyName", "producto_id"),
                Map.of("role", "TARGET", "entityId", "sale", "relationshipId", "sale-line",
                        "foreignKeyName", "venta_id")));
        data(line).put("association", association);
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("version", 2);
        document.put("nodes", List.of(product, sale, line));
        document.put("edges", List.of(
                edge("product-line", "product", "line", "ASSOCIATION"),
                edge("sale-line", "sale", "line", "ASSOCIATION")));

        byte[] bytes = UmlV2XmiGenerator.generate("Ventas", document);
        String xmi = new String(bytes, StandardCharsets.UTF_8);
        assertTrue(xmi.contains("xmi:type=\"uml:AssociationClass\""));
        assertTrue(xmi.contains("subtype=\"Class\""));
        assertTrue(xmi.contains("associationclass=\""));
        assertTrue(xmi.contains("conID=\""));
        assertTrue(xmi.contains("nType=\"17\""));

        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        var xml = factory.newDocumentBuilder().parse(new ByteArrayInputStream(bytes));
        assertEquals(1, xml.getElementsByTagName("connector").getLength());
        var connector = (org.w3c.dom.Element) xml.getElementsByTagName("connector").item(0);
        String connectorId = connector.getAttributeNS("http://schema.omg.org/spec/XMI/2.1", "idref");
        assertEquals("EAID_connector_product_sale", connectorId);
        assertTrue(xmi.contains("subject=\"" + connectorId + "\""));
        var source = (org.w3c.dom.Element) connector.getElementsByTagName("source").item(0);
        var target = (org.w3c.dom.Element) connector.getElementsByTagName("target").item(0);
        String sourceId = source.getAttributeNS("http://schema.omg.org/spec/XMI/2.1", "idref");
        String targetId = target.getAttributeNS("http://schema.omg.org/spec/XMI/2.1", "idref");
        var extended = (org.w3c.dom.Element) connector.getElementsByTagName("extendedProperties").item(0);
        String associationClassId = extended.getAttribute("associationclass");
        assertEquals("0", extended.getAttribute("virtualInheritance"));

        var associationLinks = xml.getElementsByTagName("Association");
        assertEquals(2, associationLinks.getLength());
        var linkedOwners = new HashSet<String>();
        for (int index = 0; index < associationLinks.getLength(); index++) {
            var link = (org.w3c.dom.Element) associationLinks.item(index);
            assertEquals(connectorId, link.getAttributeNS("http://schema.omg.org/spec/XMI/2.1", "id"));
            assertEquals(sourceId, link.getAttribute("start"));
            assertEquals(targetId, link.getAttribute("end"));
            var owner = (org.w3c.dom.Element) link.getParentNode().getParentNode();
            linkedOwners.add(owner.getAttributeNS("http://schema.omg.org/spec/XMI/2.1", "idref"));
        }
        assertEquals(new HashSet<>(List.of(sourceId, targetId)), linkedOwners);

        org.w3c.dom.Element associationClass = null;
        var packagedElements = xml.getElementsByTagName("packagedElement");
        for (int index = 0; index < packagedElements.getLength(); index++) {
            var item = (org.w3c.dom.Element) packagedElements.item(index);
            if ("uml:AssociationClass".equals(item.getAttributeNS(
                    "http://schema.omg.org/spec/XMI/2.1", "type"))) associationClass = item;
        }
        assertTrue(associationClass != null);
        assertEquals(associationClassId, associationClass.getAttributeNS(
                "http://schema.omg.org/spec/XMI/2.1", "id"));
        var ownedEnds = associationClass.getElementsByTagName("ownedEnd");
        var memberEnds = associationClass.getElementsByTagName("memberEnd");
        assertEquals(2, ownedEnds.getLength());
        assertEquals(2, memberEnds.getLength());
        var ownedEndIds = new HashSet<String>();
        var memberEndIds = new HashSet<String>();
        for (int index = 0; index < 2; index++) {
            ownedEndIds.add(((org.w3c.dom.Element) ownedEnds.item(index)).getAttributeNS(
                    "http://schema.omg.org/spec/XMI/2.1", "id"));
            memberEndIds.add(((org.w3c.dom.Element) memberEnds.item(index)).getAttributeNS(
                    "http://schema.omg.org/spec/XMI/2.1", "idref"));
        }
        assertEquals(ownedEndIds, memberEndIds);

        boolean matchingEaClass = false;
        var extensionElements = xml.getElementsByTagName("element");
        for (int index = 0; index < extensionElements.getLength(); index++) {
            var item = (org.w3c.dom.Element) extensionElements.item(index);
            if (!associationClassId.equals(item.getAttributeNS(
                    "http://schema.omg.org/spec/XMI/2.1", "idref"))) continue;
            var properties = (org.w3c.dom.Element) item.getElementsByTagName("properties").item(0);
            var itemExtended = (org.w3c.dom.Element) item.getElementsByTagName("extendedProperties").item(0);
            matchingEaClass = "17".equals(properties.getAttribute("nType"))
                    && connectorId.equals(itemExtended.getAttribute("conID"));
        }
        assertTrue(matchingEaClass);

        EnterpriseArchitectXmiParser parser = new EnterpriseArchitectXmiParser();
        NormalizedDiagram normalized = parser.parse(bytes);
        assertEquals(1, normalized.associations().size());
        assertEquals("ProductoVenta", normalized.classes().stream()
                .filter(NormalizedDiagram.NormalizedClass::associationClass).findFirst().orElseThrow().name());
        assertEquals("ONE_MANY", normalized.associations().getFirst().sourceMultiplicity().name());
        assertEquals("ZERO_MANY", normalized.associations().getFirst().targetMultiplicity().name());
    }

    @Test
    void keepsAggregationAndCompositionAtTheirExplicitEndsOnRoundTrip() throws Exception {
        Map<String, Object> whole = node("whole", "Todo", "CLASS");
        Map<String, Object> shared = node("shared", "Compartida", "CLASS");
        Map<String, Object> owned = node("owned", "Propia", "CLASS");
        Map<String, Object> document = Map.of("version", 2, "nodes", List.of(whole, shared, owned),
                "edges", List.of(edge("aggregate", "whole", "shared", "AGGREGATION", "TARGET"),
                        edge("compose", "whole", "owned", "COMPOSITION")));

        byte[] bytes = UmlV2XmiGenerator.generate("Relaciones", document);
        NormalizedDiagram normalized = new EnterpriseArchitectXmiParser().parse(bytes);
        assertTrue(normalized.associations().stream().anyMatch(item -> "AGGREGATION".equals(item.relationKind())
                && "Todo".equals(className(normalized, item.sourceExternalId()))
                && "Compartida".equals(className(normalized, item.targetExternalId()))
                && "TARGET".equals(item.aggregationEnd())));
        assertTrue(normalized.associations().stream().anyMatch(item -> "COMPOSITION".equals(item.relationKind())
                && "Todo".equals(className(normalized, item.sourceExternalId()))
                && "SOURCE".equals(item.aggregationEnd())));

        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        var xml = factory.newDocumentBuilder().parse(new ByteArrayInputStream(bytes));
        var connectors = xml.getElementsByTagName("connector");
        boolean targetShared = false;
        for (int index = 0; index < connectors.getLength(); index++) {
            var connector = (org.w3c.dom.Element) connectors.item(index);
            var properties = (org.w3c.dom.Element) connector.getElementsByTagName("properties").item(0);
            if (!"Aggregation".equals(properties.getAttribute("ea_type"))) continue;
            var target = (org.w3c.dom.Element) connector.getElementsByTagName("target").item(0);
            var type = (org.w3c.dom.Element) target.getElementsByTagName("type").item(0);
            targetShared |= "shared".equals(type.getAttribute("aggregation"));
        }
        assertTrue(targetShared);
    }

    private static String className(NormalizedDiagram diagram, String externalId) {
        return diagram.classes().stream().filter(item -> externalId.equals(item.externalId()))
                .findFirst().orElseThrow().name();
    }

    private static Map<String, Object> node(String id, String name, String kind) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("name", name);
        data.put("kind", kind);
        data.put("attributes", List.of());
        data.put("methods", List.of());
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", id);
        node.put("type", "entity");
        node.put("position", Map.of("x", 120, "y", 80));
        node.put("data", data);
        return node;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(Map<String, Object> node) {
        return (Map<String, Object>) node.get("data");
    }

    private static Map<String, Object> edge(String id, String source, String target, String kind) {
        return edge(id, source, target, kind, null);
    }

    private static Map<String, Object> edge(String id, String source, String target, String kind,
            String aggregationEnd) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("relationKind", kind);
        data.put("sourceCardinality", "ONE_ONE");
        data.put("targetCardinality", "ZERO_MANY");
        if (aggregationEnd != null) data.put("aggregationEnd", aggregationEnd);
        return Map.of(
                "id", id, "source", source, "target", target, "type", "relationship",
                "data", data);
    }
}
