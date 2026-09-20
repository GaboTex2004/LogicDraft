package com.sw1.backend.generator;

import com.sw1.backend.generator.mapper.ApplicationSchemaMapper;
import com.sw1.backend.generator.schema.*;
import com.sw1.backend.generator.validation.ApplicationSchemaException;
import java.util.*;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ApplicationSchemaMapperTest {
    @Test
    void mapsSimpleEntityAndExcludesReactFlowVisualState() {
        Map<String, Object> serviceNode = node("service", "Servicio",
                field("service-id", "ID", "INTEGER", true, false),
                field("service-name", "Nombre", "VARCHAR", false, false),
                field("service-price", "Precio", "DECIMAL", false, true));
        serviceNode.put("position", Map.of("x", 100, "y", 200));
        serviceNode.put("style", Map.of("color", "red"));
        Map<String, Object> document = document(List.of(serviceNode), List.of());

        ApplicationSchema schema = ApplicationSchemaMapper.fromDocument("Mi servicio", document);

        assertEquals(1, schema.schemaVersion());
        assertEquals("Mi servicio", schema.projectName());
        assertEquals("MiServicio", schema.technicalName());
        ApplicationEntity entity = schema.entities().getFirst();
        assertEquals("service", entity.id());
        assertEquals("Servicio", entity.technicalName());
        assertEquals(List.of(CanonicalType.INTEGER, CanonicalType.STRING, CanonicalType.DECIMAL),
                entity.fields().stream().map(ApplicationField::type).toList());
        assertTrue(entity.fields().getFirst().generated());
        assertFalse(entity.fields().get(1).generated());
        assertNull(entity.association());
        Set<String> schemaComponents = Set.of(ApplicationSchema.class.getRecordComponents()).stream()
                .map(component -> component.getName()).collect(java.util.stream.Collectors.toSet());
        assertFalse(schemaComponents.contains("position"));
        assertFalse(schemaComponents.contains("style"));
    }

    @Test
    void mapsMultipleEntitiesAndPreservesBothCardinalities() {
        ApplicationSchema schema = ApplicationSchemaMapper.fromDocument("Peluqueria", document(List.of(
                entityWithId("category", "Categoria"), entityWithId("cut", "Corte"), entityWithId("user", "Usuario")),
                List.of(edge("category-cut", "category", "cut", "ONE_ONE", "ZERO_MANY"))));

        assertEquals(List.of("Categoria", "Corte", "Usuario"),
                schema.entities().stream().map(ApplicationEntity::name).toList());
        ApplicationRelationship relation = schema.relationships().getFirst();
        assertEquals("category", relation.sourceEntityId());
        assertEquals("cut", relation.targetEntityId());
        assertEquals(ApplicationCardinality.ONE_ONE, relation.sourceCardinality());
        assertEquals(ApplicationCardinality.ZERO_MANY, relation.targetCardinality());
    }

    @Test
    void mapsNamedManyToManyAndCustomJoinTableWithoutBreakingLegacyRelations() {
        Map<String, Object> named = edge("student-subject", "student", "subject", "ZERO_MANY", "ONE_MANY");
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) named.get("data");
        data.put("name", "materias optativas");
        data.put("joinTableName", "alumno_materia_optativa");
        ApplicationSchema schema = ApplicationSchemaMapper.fromDocument("Academia", document(List.of(
                entityWithId("student", "Alumno"), entityWithId("subject", "Materia")), List.of(named)));

        ApplicationRelationship relation = schema.relationships().getFirst();
        assertEquals("materias optativas", relation.name());
        assertEquals("alumno_materia_optativa", relation.joinTableName());
        assertEquals(ApplicationCardinality.ZERO_MANY, relation.sourceCardinality());
        assertEquals(ApplicationCardinality.ONE_MANY, relation.targetCardinality());
    }

    @Test
    void allowsTwoNamedRelationsButRejectsEquivalentUnnamedDuplicates() {
        var entities = List.of(entityWithId("student", "Alumno"), entityWithId("subject", "Materia"));
        Map<String, Object> required = edge("required", "student", "subject", "ZERO_MANY", "ZERO_MANY");
        Map<String, Object> optional = edge("optional", "student", "subject", "ZERO_MANY", "ZERO_MANY");
        ((Map<String, Object>) required.get("data")).put("name", "materiasObligatorias");
        ((Map<String, Object>) optional.get("data")).put("name", "materiasOptativas");
        assertEquals(2, ApplicationSchemaMapper.fromDocument("Academia", document(entities,
                List.of(required, optional))).relationships().size());

        var duplicate = edge("duplicate", "subject", "student", "ZERO_MANY", "ZERO_MANY");
        var error = assertThrows(ApplicationSchemaException.class, () ->
                ApplicationSchemaMapper.fromDocument("Academia", document(entities,
                        List.of(edge("first", "student", "subject", "ZERO_MANY", "ZERO_MANY"), duplicate))));
        assertTrue(error.getMessage().contains("duplicada"));
    }

    @Test
    void rejectsJoinTablesOnNonManyRelationsAndRepeatedExplicitNames() {
        var entities = List.of(entityWithId("a", "Alumno"), entityWithId("b", "Materia"),
                entityWithId("c", "Curso"));
        Map<String, Object> notMany = edge("not-many", "a", "b", "ONE_ONE", "ZERO_MANY");
        ((Map<String, Object>) notMany.get("data")).put("joinTableName", "invalida");
        var error = assertThrows(ApplicationSchemaException.class, () ->
                ApplicationSchemaMapper.fromDocument("Academia", document(entities, List.of(notMany))));
        assertTrue(error.getMessage().contains("no es N:M"));

        Map<String, Object> first = edge("first", "a", "b", "ZERO_MANY", "ZERO_MANY");
        Map<String, Object> second = edge("second", "a", "c", "ZERO_MANY", "ZERO_MANY");
        ((Map<String, Object>) first.get("data")).put("joinTableName", "alumno_relacion");
        ((Map<String, Object>) second.get("data")).put("joinTableName", "ALUMNO_RELACION");
        error = assertThrows(ApplicationSchemaException.class, () ->
                ApplicationSchemaMapper.fromDocument("Academia", document(entities, List.of(first, second))));
        assertTrue(error.getMessage().contains("repetida"));
    }

    @Test
    void mapsEveryCanonicalTypeAndCurrentAliases() {
        Map<String, Object> entity = node("types", "Tipos",
                field("f1", "id", "BIGINT", true, false), field("f2", "string", "String", false, true),
                field("f3", "integer", "INT", false, true), field("f4", "decimal", "Double", false, true),
                field("f5", "boolean", "BOOL", false, true), field("f6", "date", "DATE", false, true),
                field("f7", "datetime", "TIMESTAMP", false, true));
        ApplicationSchema schema = ApplicationSchemaMapper.fromDocument("Tipos", document(List.of(entity), List.of()));
        assertEquals(List.of(CanonicalType.LONG, CanonicalType.STRING, CanonicalType.INTEGER, CanonicalType.DECIMAL,
                        CanonicalType.BOOLEAN, CanonicalType.DATE, CanonicalType.DATETIME),
                schema.entities().getFirst().fields().stream().map(ApplicationField::type).toList());
    }

    @Test
    void rejectsMissingAndMultiplePrimaryKeys() {
        ApplicationSchemaException missing = assertThrows(ApplicationSchemaException.class, () ->
                ApplicationSchemaMapper.fromDocument("App", document(List.of(node("e", "Corte",
                        field("f", "Nombre", "VARCHAR", false, true))), List.of())));
        assertEquals("La entidad Corte no tiene una primary key.", missing.getMessage());

        ApplicationSchemaException multiple = assertThrows(ApplicationSchemaException.class, () ->
                ApplicationSchemaMapper.fromDocument("App", document(List.of(node("e", "Usuario",
                        field("f1", "ID", "INTEGER", true, false),
                        field("f2", "Codigo", "LONG", true, false))), List.of())));
        assertEquals("La entidad Usuario tiene mas de una primary key.", multiple.getMessage());
    }

    @Test
    void rejectsDuplicateAttributesCaseInsensitiveAndUnknownType() {
        ApplicationSchemaException duplicate = assertThrows(ApplicationSchemaException.class, () ->
                ApplicationSchemaMapper.fromDocument("App", document(List.of(node("e", "Corte",
                        field("f1", "ID", "INTEGER", true, false), field("f2", "Nombre", "VARCHAR", false, true),
                        field("f3", "nombre", "VARCHAR", false, true))), List.of())));
        assertTrue(duplicate.getMessage().contains("duplicado"));

        ApplicationSchemaException unknown = assertThrows(ApplicationSchemaException.class, () ->
                ApplicationSchemaMapper.fromDocument("App", document(List.of(node("e", "Corte",
                        field("f1", "ID", "INTEGER", true, false), field("f2", "Precio", "MONEY", false, true))), List.of())));
        assertEquals("El atributo Precio de Corte utiliza un tipo no soportado: MONEY.", unknown.getMessage());
    }

    @Test
    void rejectsBrokenRelationshipsSelfRelationsAndTechnicalCollisions() {
        ApplicationSchemaException broken = assertThrows(ApplicationSchemaException.class, () ->
                ApplicationSchemaMapper.fromDocument("App", document(List.of(entityWithId("category", "Categoria")),
                        List.of(edge("r", "category", "missing", "ONE_ONE", "ZERO_MANY")))));
        assertTrue(broken.getMessage().contains("destino inexistente"));

        assertThrows(ApplicationSchemaException.class, () -> ApplicationSchemaMapper.fromDocument("App",
                document(List.of(entityWithId("category", "Categoria")),
                        List.of(edge("r", "category", "category", "ONE_ONE", "ONE_ONE")))));

        ApplicationSchemaException collision = assertThrows(ApplicationSchemaException.class, () ->
                ApplicationSchemaMapper.fromDocument("App", document(List.of(
                        entityWithId("one", "Categoría"), entityWithId("two", "Categoria")), List.of())));
        assertTrue(collision.getMessage().contains("mismo nombre tecnico"));
    }

    @Test
    void rejectsEmptyDiagramInvalidIdsAndUnsupportedCardinality() {
        assertThrows(ApplicationSchemaException.class, () ->
                ApplicationSchemaMapper.fromDocument("App", document(List.of(), List.of())));
        assertThrows(ApplicationSchemaException.class, () ->
                ApplicationSchemaMapper.fromDocument("App", document(List.of(entityWithId(" ", "Entidad")), List.of())));
        assertThrows(ApplicationSchemaException.class, () -> ApplicationSchemaMapper.fromDocument("App",
                document(List.of(entityWithId("a", "A"), entityWithId("b", "B")),
                        List.of(edge("r", "a", "b", "MANY", "ONE_ONE")))));
    }

    @Test
    void mapsExplicitAssociativeMetadataWithoutInferringOrdinaryEntities() {
        Map<String, Object> enrollment = entityWithId("enrollment", "Inscripcion");
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) enrollment.get("data");
        data.put("association", association(true, List.of(
                endpoint("SOURCE", "student", "student-enrollment", "alumno_id"),
                endpoint("TARGET", "subject", "subject-enrollment", "materia_id"))));
        ApplicationSchema schema = ApplicationSchemaMapper.fromDocument("Academia", document(List.of(
                entityWithId("student", "Alumno"), entityWithId("subject", "Materia"), enrollment), List.of(
                edge("student-enrollment", "student", "enrollment", "ONE_ONE", "ZERO_MANY"),
                edge("subject-enrollment", "subject", "enrollment", "ONE_ONE", "ZERO_MANY"))));

        assertNull(schema.entities().get(0).association());
        assertNull(schema.entities().get(1).association());
        ApplicationAssociation mapped = schema.entities().get(2).association();
        assertNotNull(mapped);
        assertEquals(AssociationKind.MANY_TO_MANY_ASSOCIATION, mapped.kind());
        assertEquals("alumno_materia", mapped.tableName());
        assertTrue(mapped.uniquePair());
        assertEquals(List.of(AssociationEndpointRole.SOURCE, AssociationEndpointRole.TARGET),
                mapped.endpoints().stream().map(ApplicationAssociationEndpoint::role).toList());
    }

    @Test
    void rejectsInvalidAssociativeStructureAndOriginalManyToManyCoexistence() {
        assertInvalidAssociation(association(false, validEndpoints()), "unicidad");
        assertInvalidAssociation(association(true, List.of(validEndpoints().getFirst())), "exactamente dos");
        assertInvalidAssociation(association(true, List.of(
                endpoint("SOURCE", "student", "student-enrollment", "alumno_id"),
                endpoint("SOURCE", "subject", "subject-enrollment", "materia_id"))), "role");
        assertInvalidAssociation(association(true, List.of(
                endpoint("SOURCE", "missing", "student-enrollment", "alumno_id"),
                validEndpoints().get(1))), "inexistente");
        assertInvalidAssociation(association(true, List.of(
                endpoint("SOURCE", "student", "missing-edge", "alumno_id"),
                validEndpoints().get(1))), "no conecta");
        assertInvalidAssociation(association(true, List.of(
                endpoint("SOURCE", "student", "student-enrollment", "entidad_id"),
                endpoint("TARGET", "subject", "subject-enrollment", "entidad_id"))), "foreign keys duplicadas");
        assertInvalidAssociation(association(true, List.of(
                endpoint("SOURCE", "student", "student-enrollment", "alumno_id"),
                endpoint("TARGET", "subject", "student-enrollment", "materia_id"))), "repetida");

        Map<String, Object> collidingTable = association(true, validEndpoints());
        collidingTable.put("tableName", "alumno");
        assertInvalidAssociation(collidingTable, "tabla fisica");

        Map<String, Object> enrollment = associativeNode(association(true, validEndpoints()));
        var error = assertThrows(ApplicationSchemaException.class, () -> ApplicationSchemaMapper.fromDocument(
                "Academia", document(List.of(entityWithId("student", "Alumno"),
                        entityWithId("subject", "Materia"), enrollment), List.of(
                        edge("student-enrollment", "student", "enrollment", "ONE_ONE", "ZERO_MANY"),
                        edge("subject-enrollment", "subject", "enrollment", "ONE_ONE", "ZERO_MANY"),
                        edge("old-many", "student", "subject", "ZERO_MANY", "ZERO_MANY")))));
        assertTrue(error.getMessage().contains("no puede coexistir"));
    }

    @Test
    void rejectsAssociativeEntityWithoutIndependentGeneratedPrimaryKey() {
        Map<String, Object> enrollment = node("enrollment", "Inscripcion",
                field("enrollment-code", "codigo", "VARCHAR", true, false));
        ((Map<String, Object>) enrollment.get("data")).put("association", association(true, validEndpoints()));
        var error = assertThrows(ApplicationSchemaException.class, () -> ApplicationSchemaMapper.fromDocument(
                "Academia", document(List.of(entityWithId("student", "Alumno"),
                        entityWithId("subject", "Materia"), enrollment), List.of(
                        edge("student-enrollment", "student", "enrollment", "ONE_ONE", "ZERO_MANY"),
                        edge("subject-enrollment", "subject", "enrollment", "ONE_ONE", "ZERO_MANY")))));
        assertTrue(error.getMessage().contains("primary key INTEGER o LONG generada"));
    }

    private static void assertInvalidAssociation(Map<String, Object> association, String expectedMessage) {
        Map<String, Object> enrollment = associativeNode(association);
        var error = assertThrows(ApplicationSchemaException.class, () -> ApplicationSchemaMapper.fromDocument(
                "Academia", document(List.of(entityWithId("student", "Alumno"),
                        entityWithId("subject", "Materia"), enrollment), List.of(
                        edge("student-enrollment", "student", "enrollment", "ONE_ONE", "ZERO_MANY"),
                        edge("subject-enrollment", "subject", "enrollment", "ONE_ONE", "ZERO_MANY")))));
        assertTrue(error.getMessage().contains(expectedMessage), error.getMessage());
    }

    private static Map<String, Object> associativeNode(Map<String, Object> association) {
        Map<String, Object> enrollment = entityWithId("enrollment", "Inscripcion");
        ((Map<String, Object>) enrollment.get("data")).put("association", association);
        return enrollment;
    }

    private static List<Map<String, Object>> validEndpoints() {
        return List.of(endpoint("SOURCE", "student", "student-enrollment", "alumno_id"),
                endpoint("TARGET", "subject", "subject-enrollment", "materia_id"));
    }

    private static Map<String, Object> association(boolean uniquePair, List<Map<String, Object>> endpoints) {
        return new LinkedHashMap<>(Map.of("kind", "MANY_TO_MANY_ASSOCIATION",
                "tableName", "alumno_materia", "uniquePair", uniquePair, "endpoints", endpoints));
    }

    private static Map<String, Object> endpoint(String role, String entityId,
                                                String relationshipId, String foreignKeyName) {
        return Map.of("role", role, "entityId", entityId,
                "relationshipId", relationshipId, "foreignKeyName", foreignKeyName);
    }

    private static Map<String, Object> document(List<Map<String, Object>> nodes, List<Map<String, Object>> edges) {
        return new LinkedHashMap<>(Map.of("version", 1, "nodes", nodes, "edges", edges));
    }

    private static Map<String, Object> entityWithId(String id, String name) {
        return node(id, name, field(id + "-id", "ID", "INTEGER", true, false));
    }

    @SafeVarargs
    private static Map<String, Object> node(String id, String name, Map<String, Object>... fields) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("name", name);
        data.put("attributes", List.of(fields));
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", id);
        node.put("type", "entity");
        node.put("data", data);
        return node;
    }

    private static Map<String, Object> field(String id, String name, String type, boolean pk, boolean nullable) {
        return Map.of("id", id, "name", name, "type", type, "primaryKey", pk, "nullable", nullable);
    }

    private static Map<String, Object> edge(String id, String source, String target, String sourceCard, String targetCard) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sourceCardinality", sourceCard);
        data.put("targetCardinality", targetCard);
        Map<String, Object> edge = new LinkedHashMap<>();
        edge.put("id", id);
        edge.put("source", source);
        edge.put("target", target);
        edge.put("type", "relationship");
        edge.put("data", data);
        return edge;
    }
}
