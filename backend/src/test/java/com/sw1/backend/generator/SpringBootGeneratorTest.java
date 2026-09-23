package com.sw1.backend.generator;

import com.sw1.backend.generator.schema.*;
import com.sw1.backend.generator.spring.*;
import com.sw1.backend.generator.validation.ApplicationSchemaException;
import java.util.List;
import org.junit.jupiter.api.Test;

import static com.sw1.backend.generator.GeneratorTestSchemas.*;
import static org.junit.jupiter.api.Assertions.*;

class SpringBootGeneratorTest {
    private final SpringBootGenerator generator = new SpringBootGenerator();

    @Test
    void generatesCompleteServiceProject() {
        GeneratedProject project = generator.generate(serviceSchema());
        String root = "src/main/java/com/logicdraft/generated/servicios/";

        assertEquals("servicios-backend", project.rootDirectoryName());
        assertTrue(project.files().keySet().containsAll(List.of(
                "pom.xml", "src/main/resources/application.properties", "README.md", "API.md", ".env.example",
                "docker-compose.yml", root + "ServiciosApplication.java", root + "entity/Servicio.java",
                root + "repository/ServicioRepository.java", root + "service/ServicioService.java",
                root + "controller/ServicioController.java", root + "dto/ServicioCreateRequest.java",
                root + "dto/ServicioUpdateRequest.java", root + "dto/ServicioResponse.java")));
        assertTrue(project.files().get("pom.xml").contains("<java.version>21</java.version>"));
        assertTrue(project.files().get("pom.xml").contains("spring-boot-starter-data-jpa"));
        assertFalse(project.files().get("pom.xml").contains("spring-security"));
        assertFalse(project.files().get("pom.xml").contains("ollama"));
        assertTrue(project.files().get("src/main/resources/application.properties").contains("servicios_db"));
        assertTrue(project.files().containsKey("docker-compose.yml"));

        String readme = project.files().get("README.md");
        assertTrue(readme.contains("## Escenario 1: primera ejecucion de un proyecto nuevo"));
        assertTrue(readme.contains("## Escenario 2: PostgreSQL o proyecto ya configurado"));
        assertTrue(readme.contains("## Estructura del proyecto"));
        assertTrue(readme.contains("## Conectar un frontend externo y CORS"));
        assertTrue(readme.contains("DB_USER"));
        assertTrue(readme.contains("DB_USERNAME"));
        assertTrue(readme.contains("API.md"));

        String entity = project.files().get(root + "entity/Servicio.java");
        assertTrue(entity.contains("@GeneratedValue(strategy = GenerationType.IDENTITY)"));
        assertTrue(entity.contains("private Integer id;"));
        assertTrue(entity.contains("private BigDecimal precio;"));
        assertTrue(entity.contains("@Table(name = \"servicio\")"));
        assertTrue(entity.contains("@Column(name = \"nombre\", nullable = false)"));
        assertTrue(project.files().get(root + "repository/ServicioRepository.java")
                .contains("JpaRepository<Servicio, Integer>"));
        assertTrue(project.files().get(root + "controller/ServicioController.java")
                .contains("@RequestMapping(\"/api/servicio\")"));
    }

    @Test
    void generatesApiDocumentationFromRealSchemaAndRelations() {
        GeneratedProject project = generator.generate(flutterAllTypesSchema());
        String api = project.files().get("API.md");

        assertNotNull(api);
        assertTrue(api.contains("# API REST de Registros"));
        assertTrue(api.contains("### `GET /api/registro`"));
        assertTrue(api.contains("### `GET /api/registro/{id}`"));
        assertTrue(api.contains("### `POST /api/registro`"));
        assertTrue(api.contains("### `PUT /api/registro/{id}`"));
        assertTrue(api.contains("### `DELETE /api/registro/{id}`"));
        assertTrue(api.contains("\"texto\": \"ejemplo\""));
        assertTrue(api.contains("\"cantidad\": 1"));
        assertTrue(api.contains("\"monto\": 10.50"));
        assertTrue(api.contains("\"activo\": true"));
        assertTrue(api.contains("\"fecha\": \"2026-01-15\""));
        assertTrue(api.contains("\"instante\": \"2026-01-15T10:30:00\""));
        assertTrue(api.contains("\"eventoIds\": [1, 2]"));
        assertTrue(api.contains("Relacion con Evento"));
        String postSection = api.substring(api.indexOf("### `POST /api/registro`"),
                api.indexOf("### `PUT /api/registro/{id}`"));
        String postBody = postSection.substring(postSection.indexOf("Body de ejemplo:"),
                postSection.indexOf("Respuesta de ejemplo:"));
        assertFalse(postBody.contains("\"id\""));
    }

    @Test
    void generatesConfigurablePortsAndTroubleshootingInstructions() {
        GeneratedProject project = generator.generate(serviceSchema());
        String properties = project.files().get("src/main/resources/application.properties");
        String env = project.files().get(".env.example");
        String compose = project.files().get("docker-compose.yml");
        String readme = project.files().get("README.md");

        assertTrue(properties.contains("server.port=${SERVER_PORT:8080}"));
        assertTrue(properties.contains(
                "spring.datasource.url=${DB_URL:jdbc:postgresql://localhost:5434/servicios_db}"));
        assertTrue(env.contains("SERVER_PORT=8080"));
        assertTrue(env.contains("DB_URL=jdbc:postgresql://localhost:5434/servicios_db"));
        assertTrue(compose.contains("- \"5434:5432\""));

        assertTrue(readme.contains("## Solución de problemas"));
        assertTrue(readme.contains("docker compose up -d"));
        assertTrue(readme.contains("docker compose ps"));
        assertTrue(readme.contains("password authentication failed"));
        assertTrue(readme.contains("Si el puerto PostgreSQL `5434` esta ocupado"));
        assertTrue(readme.contains("Si el puerto HTTP `8080` esta ocupado"));
        assertTrue(readme.contains("SERVER_PORT"));
        assertTrue(readme.contains("Web server failed to start. Port 8080 was already in use."));
        assertTrue(readme.contains("$env:SERVER_PORT=\"8082\""));
        assertTrue(readme.contains("set SERVER_PORT=8082"));
        assertTrue(readme.contains("API_BASE_URL=http://localhost:8082"));
        assertTrue(readme.contains("`SERVER_PORT` y `API_BASE_URL` deben coincidir"));
        assertTrue(readme.contains("Abrir `/` no representa necesariamente un error"));
        assertTrue(readme.contains("Una respuesta `[]`"));
        assertTrue(readme.contains("Spring no carga archivos `.env` automaticamente"));
        assertTrue(readme.contains("`spring.jpa.hibernate.ddl-auto=update` es solo para desarrollo"));
        assertTrue(readme.contains("En produccion utiliza migraciones"));
        assertTrue(readme.contains("## Relaciones muchos a muchos"));
        assertTrue(readme.contains("dos foreign keys y una restriccion UNIQUE"));
        assertTrue(readme.contains("no se inventan IDs ni se crean registros relacionados implicitamente"));
    }

    @Test
    void mapsAllCanonicalTypesToJavaAndPostgresql() {
        assertMapping(CanonicalType.STRING, "String", "VARCHAR");
        assertMapping(CanonicalType.INTEGER, "Integer", "INTEGER");
        assertMapping(CanonicalType.LONG, "Long", "BIGINT");
        assertMapping(CanonicalType.DECIMAL, "BigDecimal", "NUMERIC");
        assertMapping(CanonicalType.BOOLEAN, "Boolean", "BOOLEAN");
        assertMapping(CanonicalType.DATE, "LocalDate", "DATE");
        assertMapping(CanonicalType.DATETIME, "LocalDateTime", "TIMESTAMP");
    }

    @Test
    void respectsLongGeneratedAndStringNonGeneratedPrimaryKeys() {
        var longEntity = entity("longEntity", "LongEntity", CanonicalType.LONG, true);
        var stringEntity = entity("stringEntity", "StringEntity", CanonicalType.STRING, false);
        GeneratedProject project = generator.generate(schema("Keys", List.of(longEntity, stringEntity), List.of()));
        String root = "src/main/java/com/logicdraft/generated/keys/entity/";
        assertTrue(project.files().get(root + "LongEntity.java").contains("private Long id;"));
        assertTrue(project.files().get(root + "LongEntity.java").contains("@GeneratedValue"));
        assertTrue(project.files().get(root + "StringEntity.java").contains("private String id;"));
        assertFalse(project.files().get(root + "StringEntity.java").contains("@GeneratedValue"));
    }

    @Test
    void generatesOneToManyOwnerForeignKeyAndIdDtos() {
        GeneratedProject project = generator.generate(mainSchema());
        String root = "src/main/java/com/logicdraft/generated/peluqueria/";
        String cut = project.files().get(root + "entity/Corte.java");
        assertTrue(cut.contains("@ManyToOne(fetch = FetchType.LAZY, optional = false)"));
        assertTrue(cut.contains("@JoinColumn(name = \"categoria_id\", nullable = false)"));
        assertTrue(cut.contains("private Categoria categoria;"));
        assertFalse(project.files().get(root + "entity/Categoria.java").contains("List<Corte>"));
        assertTrue(project.files().get(root + "dto/CorteCreateRequest.java").contains("@NotNull Integer categoriaId"));
        assertTrue(project.files().get(root + "service/CorteService.java").contains("categoriaRepository.findById"));
    }

    @Test
    void supportsOneToOneReverseOneManyAndManyToMany() {
        var a = entity("a", "Alpha", CanonicalType.INTEGER, true);
        var b = entity("b", "Beta", CanonicalType.INTEGER, true);
        for (ApplicationRelationship relation : List.of(
                new ApplicationRelationship("one", "a", "b", ApplicationCardinality.ZERO_ONE, ApplicationCardinality.ONE_ONE),
                new ApplicationRelationship("reverse", "a", "b", ApplicationCardinality.ZERO_MANY, ApplicationCardinality.ONE_ONE),
                new ApplicationRelationship("many", "a", "b", ApplicationCardinality.ZERO_MANY, ApplicationCardinality.ONE_MANY))) {
            GeneratedProject project = generator.generate(schema("Relation" + relation.id(), List.of(a, b), List.of(relation)));
            String sources = String.join("\n", project.files().values());
            assertTrue(sources.contains(switch (relation.id()) {
                case "one" -> "@OneToOne";
                case "reverse" -> "@ManyToOne";
                default -> "@ManyToMany";
            }));
        }
    }

    @Test
    void enforcesOneToManyMinimumForManyToManyRequests() {
        var a = entity("a", "Alpha", CanonicalType.INTEGER, true);
        var b = entity("b", "Beta", CanonicalType.INTEGER, true);
        var relation = new ApplicationRelationship("required-many", "a", "b",
                ApplicationCardinality.ZERO_MANY, ApplicationCardinality.ONE_MANY);
        var project = generator.generate(schema("RequiredMany", List.of(a, b), List.of(relation)));
        String request = project.files().get(
                "src/main/java/com/logicdraft/generated/requiredmany/dto/AlphaCreateRequest.java");

        assertTrue(request.contains("@NotEmpty Set<Integer> betaIds"));
    }

    @Test
    void generatesOneOwnedManyToManyJoinTableWithUniquePairAndSafeServices() {
        GeneratedProject project = generator.generate(manyToManySchema());
        String root = "src/main/java/com/logicdraft/generated/academia/";
        String student = project.files().get(root + "entity/Alumno.java");
        String subject = project.files().get(root + "entity/Materia.java");
        String request = project.files().get(root + "dto/AlumnoCreateRequest.java");
        String service = project.files().get(root + "service/AlumnoService.java");

        assertEquals(1, count(student + subject, "@ManyToMany"));
        assertTrue(student.contains("@JoinTable(name = \"alumno_materia\""));
        assertTrue(student.contains("@JoinColumn(name = \"alumno_id\", nullable = false)"));
        assertTrue(student.contains("@JoinColumn(name = \"materia_id\", nullable = false)"));
        assertTrue(student.contains("@UniqueConstraint(name = \"uk_alumno_materia_pair\""));
        assertFalse(student.contains("cascade ="));
        assertFalse(subject.contains("@ManyToMany"));
        assertTrue(request.contains("Set<Integer> materiasIds"));
        assertTrue(service.contains("materiaRepository.findAllById(materiasIds)"));
        assertTrue(service.contains("if (materiasValues.size() != materiasIds.size())"));
        assertTrue(service.contains("entity.setMaterias(materiasValues)"));
    }

    @Test
    void supportsParallelNamedManyToManyAndRejectsJoinTableCollisions() {
        var student = entity("student", "Alumno", CanonicalType.INTEGER, true);
        var subject = entity("subject", "Materia", CanonicalType.INTEGER, true);
        var required = new ApplicationRelationship("required", "student", "subject",
                ApplicationCardinality.ZERO_MANY, ApplicationCardinality.ZERO_MANY,
                "materiasObligatorias", null);
        var optional = new ApplicationRelationship("optional", "student", "subject",
                ApplicationCardinality.ZERO_MANY, ApplicationCardinality.ZERO_MANY,
                "materiasOptativas", null);
        String sources = String.join("\n", generator.generate(schema("Academia", List.of(student, subject),
                List.of(required, optional))).files().values());
        assertTrue(sources.contains("alumno_materia_materias_obligatorias"));
        assertTrue(sources.contains("alumno_materia_materias_optativas"));

        var collision = new ApplicationRelationship("collision", "student", "subject",
                ApplicationCardinality.ZERO_MANY, ApplicationCardinality.ZERO_MANY,
                "otrasMaterias", "alumno_materia_materias_obligatorias");
        var error = assertThrows(ApplicationSchemaException.class, () -> generator.generate(schema("Academia",
                List.of(student, subject), List.of(required, collision))));
        assertTrue(error.getMessage().contains("tabla intermedia"));

        var tooLong = new ApplicationRelationship("long", "student", "subject",
                ApplicationCardinality.ZERO_MANY, ApplicationCardinality.ZERO_MANY,
                "materias", "esta_tabla_intermedia_tiene_un_nombre_excesivamente_largo_para_postgresql_seguro");
        var longError = assertThrows(SpringGeneratorException.class, () -> generator.generate(schema("Academia",
                List.of(student, subject), List.of(tooLong))));
        assertTrue(longError.getMessage().contains("63 caracteres") || longError.getMessage().contains("demasiado larga"));
    }

    @Test
    void modelsAnAssociativeEntityWithAttributesAsTwoManyToOneRelations() {
        String sources = generator.generate(associativeEntitySchema()).files().entrySet().stream()
                .filter(entry -> entry.getKey().contains("/entity/") && entry.getKey().endsWith(".java"))
                .map(java.util.Map.Entry::getValue).collect(java.util.stream.Collectors.joining("\n"));
        assertFalse(sources.contains("@ManyToMany"));
        assertEquals(2, count(sources, "@ManyToOne"));
        assertTrue(sources.contains("private LocalDate fecha;"));
        assertTrue(sources.contains("private BigDecimal nota;"));
    }

    @Test
    void generatesExplicitAssociationTableCrudAndMetadataForeignKeys() {
        GeneratedProject project = generator.generate(associativeMetadataSchema());
        String root = "src/main/java/com/logicdraft/generated/academiaasociativa/";
        String entity = project.files().get(root + "entity/Inscripcion.java");
        String create = project.files().get(root + "dto/InscripcionCreateRequest.java");
        String update = project.files().get(root + "dto/InscripcionUpdateRequest.java");
        String response = project.files().get(root + "dto/InscripcionResponse.java");
        String service = project.files().get(root + "service/InscripcionService.java");

        assertTrue(entity.contains("@Table(name = \"alumno_materia\""));
        assertTrue(entity.contains("@UniqueConstraint(name = \"uk_alumno_materia_pair\", columnNames = {\"alumno_ref\", \"materia_ref\"})"));
        assertEquals(2, count(entity, "@ManyToOne(fetch = FetchType.LAZY, optional = false)"));
        assertTrue(entity.contains("@JoinColumn(name = \"alumno_ref\", nullable = false)"));
        assertTrue(entity.contains("@JoinColumn(name = \"materia_ref\", nullable = false)"));
        assertFalse(entity.contains("@ManyToMany"));
        assertFalse(entity.contains("@JoinTable"));
        assertFalse(entity.contains("cascade ="));
        assertTrue(entity.contains("private Integer nota;"));
        assertTrue(entity.contains("private LocalDate fechaInscripcion;"));
        assertTrue(create.contains("@NotNull Integer alumnoId"));
        assertTrue(create.contains("@NotNull Long materiaId"));
        assertTrue(update.contains("@NotNull Integer alumnoId"));
        assertTrue(response.contains("Integer alumnoId"));
        assertTrue(response.contains("Long materiaId"));
        assertTrue(service.contains("alumnoRepository.findById(request.alumnoId())"));
        assertTrue(service.contains("materiaRepository.findById(request.materiaId())"));
        assertTrue(service.contains("inscripcionRepository.delete(buscar(id))"));
        assertTrue(project.files().get(root + "error/GlobalExceptionHandler.java")
                .contains("verifica combinaciones unicas y referencias"));
    }

    @Test
    void supportsIntegerAndLongAssociationEndpointsWithoutChangingPhysicalNames() {
        for (var types : List.of(
                List.of(CanonicalType.INTEGER, CanonicalType.INTEGER),
                List.of(CanonicalType.LONG, CanonicalType.LONG))) {
            GeneratedProject project = generator.generate(associativeMetadataSchema(types.get(0), types.get(1)));
            String root = "src/main/java/com/logicdraft/generated/academiaasociativa/";
            String request = project.files().get(root + "dto/InscripcionCreateRequest.java");
            String entity = project.files().get(root + "entity/Inscripcion.java");
            String javaType = types.get(0) == CanonicalType.INTEGER ? "Integer" : "Long";
            assertTrue(request.contains("@NotNull " + javaType + " alumnoId"));
            assertTrue(request.contains("@NotNull " + javaType + " materiaId"));
            assertTrue(entity.contains("@Table(name = \"alumno_materia\""));
        }
    }

    @Test
    void rejectsJavaAndPostgresqlReservedNames() {
        var javaReserved = new ApplicationEntity("class", "Class", "Class", List.of(
                field("class", "Id", CanonicalType.INTEGER, true, false, true)));
        assertThrows(SpringGeneratorException.class, () -> generator.generate(schema("App", List.of(javaReserved), List.of())));
        var sqlReserved = new ApplicationEntity("order", "Order", "Order", List.of(
                field("order", "Id", CanonicalType.INTEGER, true, false, true)));
        SpringGeneratorException error = assertThrows(SpringGeneratorException.class,
                () -> generator.generate(schema("App", List.of(sqlReserved), List.of())));
        assertTrue(error.getMessage().contains("PostgreSQL"));
    }

    @Test
    void derivesSafePackageDatabaseAndSnakeCaseNames() {
        assertEquals("com.logicdraft.generated.peluqueriacentral", SpringNames.basePackage("PeluqueriaCentral"));
        assertEquals("detalle_venta", SpringNames.sqlName("DetalleVenta", "tabla"));
        assertEquals("fecha_creacion", SpringNames.sqlName("fechaCreacion", "columna"));
        assertEquals("/api/detalle_venta", SpringNames.restRoute("DetalleVenta"));
    }

    @Test
    void acceptsDisplayNamesWithAccentsUsingStableTechnicalNames() {
        var entity = entity("salon", "Peluquería Central", CanonicalType.INTEGER, true);
        var normalizedEntity = new ApplicationEntity(entity.id(), entity.name(), "PeluqueriaCentral", entity.fields());
        ApplicationSchema schema = new ApplicationSchema(1, "Peluquería Central", "Peluquería Central",
                "PeluqueriaCentral", List.of(normalizedEntity), List.of());
        GeneratedProject project = generator.generate(schema);
        assertEquals("peluqueriacentral-backend.zip", project.downloadFileName());
        assertTrue(project.files().keySet().stream().anyMatch(path -> path.endsWith("/entity/PeluqueriaCentral.java")));
    }

    private static void assertMapping(CanonicalType type, String javaType, String postgres) {
        assertEquals(javaType, SpringTypeMapper.map(type).javaType());
        assertEquals(postgres, SpringTypeMapper.map(type).postgresType());
    }

    private static int count(String value, String token) {
        return (value.length() - value.replace(token, "").length()) / token.length();
    }
}
