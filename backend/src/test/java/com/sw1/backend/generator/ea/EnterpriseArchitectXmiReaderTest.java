package com.sw1.backend.generator.ea;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.sw1.backend.ai.diagram.model.DiagramCardinality;
import com.sw1.backend.ai.diagram.validation.DiagramContextMapper;
import com.sw1.backend.diagrama.validation.DiagramDocumentValidator;
import com.sw1.backend.generator.ea.NormalizedDiagram.NormalizedAssociation;
import com.sw1.backend.generator.ea.NormalizedDiagram.NormalizedClass;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.springframework.web.server.ResponseStatusException;

class EnterpriseArchitectXmiReaderTest {
    private byte[] xmi;
    private EnterpriseArchitectXmiParser parser;
    private EnterpriseArchitectXmiReader reader;

    @BeforeEach
    void setUp() throws IOException {
        parser = new EnterpriseArchitectXmiParser();
        reader = new EnterpriseArchitectXmiReader(parser);
        try (InputStream input = getClass().getResourceAsStream("/generator/ea/ExamenVentas.xmi")) {
            assertNotNull(input);
            xmi = input.readAllBytes();
        }
    }

    @Test
    void parsesRealExamenVentasClassesAssociationClassAttributesAndTypes() {
        NormalizedDiagram diagram = parser.parse(xmi);

        assertEquals("Starter Class Diagram", diagram.projectName());
        assertEquals(List.of("Productos", "Usuario", "Venta", "ventaProducto"),
                diagram.classes().stream().map(NormalizedClass::name).toList());
        assertFalse(byName(diagram, "Productos").associationClass());
        assertTrue(byName(diagram, "ventaProducto").associationClass());
        assertEquals(List.of("ID:INTEGER", "Nombre:VARCHAR", "precio:DECIMAL"), attributes(diagram, "Productos"));
        assertEquals(List.of("ID:INTEGER", "Nombre:VARCHAR", "Telefono:VARCHAR"), attributes(diagram, "Usuario"));
        assertEquals(List.of("fecha:DATE", "ID:INTEGER", "precio:DECIMAL"), attributes(diagram, "Venta"));
        assertEquals(List.of("cantidad:INTEGER"), attributes(diagram, "ventaProducto"));
    }

    @Test
    void resolvesCardinalitiesByEndpointIdWithoutInvertingThem() {
        NormalizedDiagram diagram = parser.parse(xmi);
        NormalizedAssociation usuarioVenta = association(diagram, "Usuario", "Venta");
        assertEquals(DiagramCardinality.ONE_ONE, usuarioVenta.sourceMultiplicity());
        assertEquals(DiagramCardinality.ONE_MANY, usuarioVenta.targetMultiplicity());

        NormalizedAssociation productosVenta = association(diagram, "Productos", "Venta");
        assertEquals(DiagramCardinality.ONE_MANY, productosVenta.sourceMultiplicity());
        assertEquals(DiagramCardinality.ONE_ONE, productosVenta.targetMultiplicity());
        assertEquals(byName(diagram, "ventaProducto").externalId(), productosVenta.associationClassExternalId());
        assertTrue(diagram.warnings().stream().anyMatch(warning -> warning.equals(
                "Multiplicidad no reconocida \"*...1\" en la relación Productos ↔ Venta; se usará 1..1.")));
    }

    @Test
    void normalizesOnlySupportedMultiplicities() {
        assertEquals(DiagramCardinality.ONE_ONE, EnterpriseArchitectXmiParser.normalizeMultiplicity("  +1..1 "));
        assertEquals(DiagramCardinality.ZERO_ONE, EnterpriseArchitectXmiParser.normalizeMultiplicity("0..1"));
        assertEquals(DiagramCardinality.ZERO_MANY, EnterpriseArchitectXmiParser.normalizeMultiplicity("*"));
        assertEquals(DiagramCardinality.ZERO_MANY, EnterpriseArchitectXmiParser.normalizeMultiplicity("0..N"));
        assertEquals(DiagramCardinality.ONE_MANY, EnterpriseArchitectXmiParser.normalizeMultiplicity("1..*"));
        assertNull(EnterpriseArchitectXmiParser.normalizeMultiplicity("*...1"));
        assertNull(EnterpriseArchitectXmiParser.normalizeMultiplicity("cualquiera"));
    }

    @Test
    void parsesUpdatedExternalExamenVentasArtifactWhenProvided() throws IOException {
        String configured = System.getProperty("ea.real.xmi");
        Assumptions.assumeTrue(configured != null && Files.isRegularFile(Path.of(configured)),
                "Set -Dea.real.xmi to execute this check against the manual EA export.");
        NormalizedDiagram diagram = parser.parse(Files.readAllBytes(Path.of(configured)));

        assertEquals(List.of("Productos", "Usuario", "Venta", "ventaProducto"),
                diagram.classes().stream().map(NormalizedClass::name).toList());
        NormalizedAssociation usuarioVenta = association(diagram, "Usuario", "Venta");
        assertEquals(DiagramCardinality.ONE_ONE, usuarioVenta.sourceMultiplicity());
        assertEquals(DiagramCardinality.ONE_MANY, usuarioVenta.targetMultiplicity());
        assertTrue(byName(diagram, "ventaProducto").associationClass());
        assertTrue(diagram.warnings().stream().anyMatch(warning -> warning.contains("*...1")
                && warning.contains("Productos ↔ Venta")));
    }

    @Test
    void keepsGeneralizationsNormalizedAndWarnsWhenMappingUnsupportedEditorSemantics() {
        byte[] inheritance = """
                <xmi:XMI xmi:version="2.1" xmlns:xmi="http://schema.omg.org/spec/XMI/2.1"
                         xmlns:uml="http://schema.omg.org/spec/UML/2.1">
                  <uml:Model xmi:id="model" name="Model">
                    <packagedElement xmi:type="uml:Package" xmi:id="package" name="Herencia">
                      <packagedElement xmi:type="uml:Class" xmi:id="base" name="Base"/>
                      <packagedElement xmi:type="uml:Class" xmi:id="child" name="Hija">
                        <generalization xmi:id="generalization" general="base"/>
                      </packagedElement>
                    </packagedElement>
                  </uml:Model>
                </xmi:XMI>
                """.getBytes(StandardCharsets.UTF_8);

        NormalizedDiagram normalized = parser.parse(inheritance);
        assertEquals(1, normalized.generalizations().size());
        assertEquals("child", normalized.generalizations().getFirst().specificExternalId());
        assertEquals("base", normalized.generalizations().getFirst().generalExternalId());
        EnterpriseArchitectImportPreview preview = EnterpriseArchitectDiagramMapper.toPreview(normalized);
        assertEquals(2, preview.version());
        assertTrue(preview.edges().stream().anyMatch(edge -> "GENERALIZATION".equals(
                map(map(edge).get("data")).get("relationKind"))));
    }

    @Test
    void importsUmlV2ClassifiersOperationsRelationsAndDiagramBounds() {
        byte[] uml = """
                <xmi:XMI xmi:version="2.1" xmlns:xmi="http://schema.omg.org/spec/XMI/2.1"
                         xmlns:uml="http://schema.omg.org/spec/UML/2.1">
                  <uml:Model xmi:type="uml:Model" xmi:id="model" name="Model">
                    <packagedElement xmi:type="uml:Package" xmi:id="package" name="UML avanzado">
                      <packagedElement xmi:type="uml:Interface" xmi:id="contract" name="Consultable"/>
                      <packagedElement xmi:type="uml:Enumeration" xmi:id="status" name="Estado">
                        <ownedLiteral xmi:type="uml:EnumerationLiteral" xmi:id="active" name="ACTIVO"/>
                        <ownedLiteral xmi:type="uml:EnumerationLiteral" xmi:id="inactive" name="INACTIVO"/>
                      </packagedElement>
                      <packagedElement xmi:type="uml:Class" xmi:id="service" name="Servicio">
                        <ownedAttribute xmi:type="uml:Property" xmi:id="code" name="codigo" visibility="protected">
                          <type xmi:type="uml:PrimitiveType" href="http://schema.omg.org/spec/UML/2.1/uml.xml#String"/>
                        </ownedAttribute>
                        <ownedOperation xmi:type="uml:Operation" xmi:id="find" name="buscar" visibility="public">
                          <ownedParameter xmi:type="uml:Parameter" xmi:id="query" name="consulta" type="EAJava_String" direction="in"/>
                          <ownedParameter xmi:type="uml:Parameter" xmi:id="return" name="return" direction="return"><type xmi:idref="status"/></ownedParameter>
                        </ownedOperation>
                        <interfaceRealization xmi:type="uml:InterfaceRealization" xmi:id="realizes" supplier="contract"/>
                      </packagedElement>
                      <packagedElement xmi:type="uml:Class" xmi:id="part" name="Parte"/>
                      <packagedElement xmi:type="uml:Association" xmi:id="composition">
                        <ownedEnd xmi:type="uml:Property" xmi:id="whole" aggregation="composite"><type xmi:idref="service"/><lowerValue value="1"/><upperValue value="1"/></ownedEnd>
                        <ownedEnd xmi:type="uml:Property" xmi:id="pieces"><type xmi:idref="part"/><lowerValue value="0"/><upperValue value="*"/></ownedEnd>
                      </packagedElement>
                      <packagedElement xmi:type="uml:Dependency" xmi:id="uses" name="consulta" client="service" supplier="status"/>
                    </packagedElement>
                  </uml:Model>
                  <xmi:Extension extender="Enterprise Architect">
                    <diagrams><diagram><model package="package"/><elements>
                      <element subject="service" geometry="Left=40;Top=60;Right=340;Bottom=240;"/>
                      <element subject="part" geometry="Left=420;Top=60;Right=620;Bottom=180;"/>
                    </elements></diagram></diagrams>
                  </xmi:Extension>
                </xmi:XMI>
                """.getBytes(StandardCharsets.UTF_8);

        NormalizedDiagram normalized = parser.parse(uml);
        assertEquals("INTERFACE", byName(normalized, "Consultable").kind());
        assertEquals(List.of("ACTIVO", "INACTIVO"), byName(normalized, "Estado").literals());
        assertEquals("Estado", byName(normalized, "Servicio").operations().getFirst().returnType());
        assertEquals("VARCHAR", byName(normalized, "Servicio").operations().getFirst().parameters().getFirst().type());
        assertEquals(300, byName(normalized, "Servicio").size().width());
        assertEquals("COMPOSITION", normalized.associations().getFirst().relationKind());
        assertEquals(1, normalized.realizations().size());
        assertEquals(1, normalized.dependencies().size());

        EnterpriseArchitectImportPreview preview = reader.read(uml);
        assertDoesNotThrow(() -> DiagramDocumentValidator.validate(document(preview)));
    }

    @Test
    void rejectsAssociationWithMissingEndpointUsingControlledError() {
        byte[] invalid = """
                <xmi:XMI xmi:version="2.1" xmlns:xmi="http://schema.omg.org/spec/XMI/2.1"
                         xmlns:uml="http://schema.omg.org/spec/UML/2.1">
                  <uml:Model xmi:id="model" name="Model"><packagedElement xmi:type="uml:Package" xmi:id="p" name="P">
                    <packagedElement xmi:type="uml:Class" xmi:id="a" name="A"/>
                    <packagedElement xmi:type="uml:Association" xmi:id="broken">
                      <ownedEnd xmi:id="one"><type xmi:idref="a"/></ownedEnd>
                      <ownedEnd xmi:id="two"><type xmi:idref="missing"/></ownedEnd>
                    </packagedElement>
                  </packagedElement></uml:Model>
                </xmi:XMI>
                """.getBytes(StandardCharsets.UTF_8);

        ResponseStatusException error = assertThrows(ResponseStatusException.class, () -> parser.parse(invalid));
        assertTrue(error.getReason().contains("extremos inexistentes"));
    }

    @Test
    void parsesHistoricalEaSamplesWhenProvided() throws IOException {
        String configured = System.getProperty("ea.samples.dir");
        Assumptions.assumeTrue(configured != null && Files.isDirectory(Path.of(configured)),
                "Set -Dea.samples.dir to execute checks against the historical EA exports.");
        byte[] ea2Bytes = Files.readAllBytes(Path.of(configured, "exportacion de EA2.xmi"));
        byte[] ea4Bytes = Files.readAllBytes(Path.of(configured, "exportacion de EA4.xmi"));
        NormalizedDiagram ea2 = parser.parse(ea2Bytes);
        NormalizedDiagram ea4 = parser.parse(ea4Bytes);

        assertTrue(ea2.classes().stream().anyMatch(item -> "INTERFACE".equals(item.kind())));
        assertTrue(ea2.classes().stream().anyMatch(item -> "ENUMERATION".equals(item.kind())));
        assertTrue(ea2.classes().stream().anyMatch(item -> !item.operations().isEmpty()));
        assertTrue(ea2.classes().stream().anyMatch(NormalizedClass::associationClass));
        assertTrue(ea2.associations().stream().anyMatch(item -> "COMPOSITION".equals(item.relationKind())));
        assertTrue(ea4.classes().stream().anyMatch(NormalizedClass::associationClass));
        NormalizedAssociation aggregation = association(ea4, "devolucion", "ventas");
        assertEquals("AGGREGATION", aggregation.relationKind());
        assertEquals("TARGET", aggregation.aggregationEnd());
        assertTrue(ea4.classes().stream().allMatch(item -> item.size().width() > 0 && item.size().height() > 0));
        EnterpriseArchitectImportPreview ea2Preview = reader.read(ea2Bytes);
        EnterpriseArchitectImportPreview ea4Preview = reader.read(ea4Bytes);
        List<Map<String, Object>> class1Attributes = maps(map(node(ea4Preview, "Class1").get("data")).get("attributes"));
        assertEquals(List.of("producto_id", "ventas_id"), class1Attributes.stream()
                .map(attribute -> attribute.get("name")).toList());
        assertTrue(class1Attributes.stream().noneMatch(attribute -> Boolean.TRUE.equals(attribute.get("primaryKey"))));
        assertDoesNotThrow(() -> DiagramDocumentValidator.validate(document(ea2Preview)));
        assertDoesNotThrow(() -> DiagramDocumentValidator.validate(document(ea4Preview)));
        NormalizedDiagram ea4RoundTrip = parser.parse(UmlV2XmiGenerator.generate(
                ea4Preview.projectName(), new LinkedHashMap<>(document(ea4Preview))));
        assertEquals(ea4.classes().size(), ea4RoundTrip.classes().size());
        assertTrue(ea4RoundTrip.classes().stream().anyMatch(NormalizedClass::associationClass));
        assertEquals(List.of("producto_id:INTEGER", "ventas_id:INTEGER"), attributes(ea4RoundTrip, "Class1"));
        NormalizedAssociation roundTripAggregation = association(ea4RoundTrip, "devolucion", "ventas");
        assertEquals("AGGREGATION", roundTripAggregation.relationKind());
        assertEquals("TARGET", roundTripAggregation.aggregationEnd());
    }

    @Test
    void mapsAssociationClassLikeTheManualEditorContractAndProducesARegularDocument() {
        EnterpriseArchitectImportPreview preview = reader.read(xmi);
        Map<String, Object> associationNode = node(preview, "ventaProducto");
        Map<String, Object> associationData = map(associationNode.get("data"));
        assertEquals("EAID_9B66B216_BBBD_4fa2_878A_50FEDA87B097",
                map(associationData.get("externalMetadata")).get("externalId"));
        List<Map<String, Object>> attributes = maps(associationData.get("attributes"));
        assertEquals(1, attributes.size());
        assertEquals("cantidad", attributes.getFirst().get("name"));
        assertEquals(false, attributes.getFirst().get("primaryKey"));
        assertEquals("EAID_F8312E5B_12F9_414d_9CCD_8F988C01D253",
                map(attributes.getFirst().get("externalMetadata")).get("externalId"));

        Map<String, Object> association = map(associationData.get("association"));
        assertEquals("MANY_TO_MANY_ASSOCIATION", association.get("kind"));
        assertEquals(true, association.get("uniquePair"));
        assertEquals("venta_producto", association.get("tableName"));
        List<Map<String, Object>> endpoints = maps(association.get("endpoints"));
        assertEquals(List.of("SOURCE", "TARGET"), endpoints.stream().map(item -> item.get("role")).toList());

        String associationId = (String) associationNode.get("id");
        List<Map<String, Object>> structural = preview.edges().stream()
                .filter(edge -> associationId.equals(edge.get("target"))).toList();
        assertEquals(2, structural.size());
        assertTrue(structural.stream().allMatch(edge -> "relationship".equals(edge.get("type"))));
        assertTrue(structural.stream().allMatch(edge -> "ONE_ONE".equals(map(edge.get("data")).get("sourceCardinality"))));
        assertTrue(structural.stream().allMatch(edge -> "enterprise-architect".equals(
                map(map(edge.get("data")).get("externalMetadata")).get("source"))));

        Map<String, Object> normal = node(preview, "Usuario");
        assertEquals("entity", normal.get("type"));
        assertFalse(map(normal.get("data")).containsKey("readOnly"));
        assertFalse(map(normal.get("data")).containsKey("imported"));
        assertDoesNotThrow(() -> DiagramContextMapper.fromDocument(document(preview)));

        // A normal editor operation remains possible: rename a non-structural entity and remove its ordinary edge.
        map(normal.get("data")).put("name", "Cliente");
        List<Map<String, Object>> editedEdges = new ArrayList<>(preview.edges());
        editedEdges.removeIf(edge -> normal.get("id").equals(edge.get("source"))
                || normal.get("id").equals(edge.get("target")));
        assertDoesNotThrow(() -> DiagramContextMapper.fromDocument(Map.of(
                "version", preview.version(), "nodes", preview.nodes(), "edges", editedEdges)));
    }

    private static Map<String, Object> document(EnterpriseArchitectImportPreview preview) {
        return Map.of("version", preview.version(), "nodes", preview.nodes(), "edges", preview.edges());
    }

    private static List<String> attributes(NormalizedDiagram diagram, String className) {
        return byName(diagram, className).attributes().stream()
                .map(attribute -> attribute.name() + ":" + attribute.type()).toList();
    }

    private static NormalizedClass byName(NormalizedDiagram diagram, String name) {
        return diagram.classes().stream().filter(item -> item.name().equals(name)).findFirst().orElseThrow();
    }

    private static NormalizedAssociation association(NormalizedDiagram diagram, String source, String target) {
        String sourceId = byName(diagram, source).externalId();
        String targetId = byName(diagram, target).externalId();
        return diagram.associations().stream().filter(item -> item.sourceExternalId().equals(sourceId)
                && item.targetExternalId().equals(targetId)).findFirst().orElseThrow();
    }

    private static Map<String, Object> node(EnterpriseArchitectImportPreview preview, String name) {
        return preview.nodes().stream().filter(item -> name.equals(map(item.get("data")).get("name")))
                .findFirst().orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> maps(Object value) {
        return (List<Map<String, Object>>) value;
    }
}
