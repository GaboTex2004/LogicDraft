package com.sw1.backend.generator;

import com.sw1.backend.generator.spring.SpringBootGenerator;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.time.Duration;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Opt-in integration against PostgreSQL 17. It creates a random isolated schema and always drops it.
 */
@Tag("postgres-integration")
class GeneratedAssociativeEntityPostgresIntegrationTest {
    @TempDir Path temporaryDirectory;

    @Test
    void generatedAssociationUsesOneRealTableWithPkForeignKeysAndUniquePair() throws Exception {
        String adminUrl = System.getenv("LOGICDRAFT_TEST_POSTGRES_URL");
        String user = System.getenv("LOGICDRAFT_TEST_POSTGRES_USER");
        String password = System.getenv("LOGICDRAFT_TEST_POSTGRES_PASSWORD");
        Assumptions.assumeTrue(adminUrl != null && !adminUrl.isBlank() && user != null && password != null,
                "Define LOGICDRAFT_TEST_POSTGRES_* para ejecutar PostgreSQL real");

        String schema = "logicdraft_assoc_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        try (Connection connection = DriverManager.getConnection(adminUrl, user, password);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA \"" + schema + "\"");
        }
        try {
            Path root = materializeGeneratedProject();
            Path generatedTest = root.resolve(
                    "src/test/java/com/logicdraft/generated/academiaasociativa/GeneratedAssociationPersistenceTest.java");
            Files.createDirectories(generatedTest.getParent());
            Files.writeString(generatedTest, generatedPersistenceTest(), StandardCharsets.UTF_8);

            Path log = temporaryDirectory.resolve("generated-association-postgres.log");
            boolean windows = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
            ProcessBuilder builder = windows
                    ? new ProcessBuilder("cmd.exe", "/c", "mvn.cmd", "-q",
                        "-Dtest=GeneratedAssociationPersistenceTest", "test")
                    : new ProcessBuilder("mvn", "-q", "-Dtest=GeneratedAssociationPersistenceTest", "test");
            String separator = adminUrl.contains("?") ? "&" : "?";
            builder.environment().put("DB_URL", adminUrl + separator + "currentSchema=" + schema);
            builder.environment().put("DB_USER", user);
            builder.environment().put("DB_PASSWORD", password);
            Process process = builder.directory(root.toFile()).redirectErrorStream(true)
                    .redirectOutput(log.toFile()).start();
            boolean finished = process.waitFor(Duration.ofMinutes(3).toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) process.destroyForcibly();
            assertTrue(finished, "El test PostgreSQL asociativo generado excedio tres minutos");
            assertEquals(0, process.exitValue(), () -> readLog(log));

            try (Connection connection = DriverManager.getConnection(adminUrl, user, password)) {
                assertEquals(1, scalar(connection, """
                        SELECT count(*) FROM information_schema.tables
                        WHERE table_schema = ? AND table_name = 'alumno_materia'
                        """, schema));
                assertEquals(2, scalar(connection, """
                        SELECT count(*) FROM information_schema.table_constraints
                        WHERE table_schema = ? AND table_name = 'alumno_materia'
                          AND constraint_type = 'FOREIGN KEY'
                        """, schema));
                assertEquals(1, scalar(connection, """
                        SELECT count(*) FROM information_schema.table_constraints
                        WHERE table_schema = ? AND table_name = 'alumno_materia'
                          AND constraint_type = 'PRIMARY KEY'
                        """, schema));
                assertEquals(1, scalar(connection, """
                        SELECT count(*) FROM information_schema.table_constraints
                        WHERE table_schema = ? AND table_name = 'alumno_materia'
                          AND constraint_type = 'UNIQUE' AND constraint_name = 'uk_alumno_materia_pair'
                        """, schema));
                assertEquals(2, scalar(connection, """
                        SELECT count(*) FROM information_schema.columns
                        WHERE table_schema = ? AND table_name = 'alumno_materia'
                          AND column_name IN ('alumno_ref', 'materia_ref') AND is_nullable = 'NO'
                        """, schema));
                assertEquals(2, scalar(connection, """
                        SELECT count(*) FROM information_schema.columns
                        WHERE table_schema = ? AND table_name = 'alumno_materia'
                          AND ((column_name = 'alumno_ref' AND data_type = 'integer')
                            OR (column_name = 'materia_ref' AND data_type = 'bigint'))
                        """, schema));
                assertEquals(2, scalar(connection, """
                        SELECT count(*) FROM information_schema.columns
                        WHERE table_schema = ? AND table_name = 'alumno_materia'
                          AND ((column_name = 'nota' AND data_type = 'integer')
                            OR (column_name = 'fecha_inscripcion' AND data_type = 'date'))
                        """, schema));
                assertEquals(1, scalar(connection,
                        "SELECT count(*) FROM \"" + schema + "\".alumno", null));
                assertEquals(1, scalar(connection,
                        "SELECT count(*) FROM \"" + schema + "\".materia", null));
                assertEquals(0, scalar(connection,
                        "SELECT count(*) FROM \"" + schema + "\".alumno_materia", null));
            }
        } finally {
            try (Connection connection = DriverManager.getConnection(adminUrl, user, password);
                 Statement statement = connection.createStatement()) {
                statement.execute("DROP SCHEMA IF EXISTS \"" + schema + "\" CASCADE");
            }
        }
    }

    private Path materializeGeneratedProject() throws Exception {
        var generated = new SpringBootGenerator().generate(GeneratorTestSchemas.associativeMetadataSchema());
        Path root = temporaryDirectory.resolve(generated.rootDirectoryName());
        for (var file : generated.files().entrySet()) {
            Path target = root.resolve(file.getKey()).normalize();
            assertTrue(target.startsWith(root));
            Files.createDirectories(target.getParent());
            Files.writeString(target, file.getValue(), StandardCharsets.UTF_8);
        }
        return root;
    }

    private static int scalar(Connection connection, String sql, String schema) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            if (schema != null) statement.setString(1, schema);
            try (ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next());
                return rows.getInt(1);
            }
        }
    }

    private static String readLog(Path log) {
        try { return Files.readString(log); }
        catch (Exception ignored) { return "No se pudo leer el log del proyecto generado"; }
    }

    private static String generatedPersistenceTest() {
        return """
                package com.logicdraft.generated.academiaasociativa;

                import com.logicdraft.generated.academiaasociativa.dto.*;
                import com.logicdraft.generated.academiaasociativa.error.ResourceNotFoundException;
                import com.logicdraft.generated.academiaasociativa.repository.*;
                import com.logicdraft.generated.academiaasociativa.service.*;
                import java.time.LocalDate;
                import org.junit.jupiter.api.Test;
                import org.springframework.beans.factory.annotation.Autowired;
                import org.springframework.boot.test.context.SpringBootTest;
                import org.springframework.dao.DataIntegrityViolationException;
                import static org.junit.jupiter.api.Assertions.*;

                @SpringBootTest
                class GeneratedAssociationPersistenceTest {
                  @Autowired AlumnoService alumnoService;
                  @Autowired MateriaService materiaService;
                  @Autowired InscripcionService inscripcionService;
                  @Autowired AlumnoRepository alumnoRepository;
                  @Autowired MateriaRepository materiaRepository;
                  @Autowired InscripcionRepository inscripcionRepository;

                  @Test
                  void persistsReadsUpdatesRejectsDuplicatesAndDeletesOnlyAssociation() {
                    var alumno = alumnoService.crear(new AlumnoCreateRequest("Ana"));
                    var materia = materiaService.crear(new MateriaCreateRequest("Bases de datos"));
                    var create = new InscripcionCreateRequest(
                        95, LocalDate.parse("2026-09-17"), alumno.id(), materia.id());
                    var created = inscripcionService.crear(create);
                    assertEquals(alumno.id(), created.alumnoId());
                    assertEquals(materia.id(), created.materiaId());
                    assertEquals(1, inscripcionService.listar().size());
                    assertEquals(95, inscripcionService.obtenerPorId(created.id()).nota());
                    var updated = inscripcionService.actualizar(created.id(), new InscripcionUpdateRequest(
                        88, LocalDate.parse("2026-09-18"), alumno.id(), materia.id()));
                    assertEquals(88, updated.nota());
                    assertThrows(DataIntegrityViolationException.class,
                        () -> inscripcionService.crear(create));
                    assertEquals(1, inscripcionRepository.count());
                    assertThrows(ResourceNotFoundException.class, () -> inscripcionService.crear(
                        new InscripcionCreateRequest(80, LocalDate.parse("2026-09-18"),
                            999999, materia.id())));
                    assertEquals(1, inscripcionRepository.count());

                    inscripcionService.eliminar(created.id());
                    assertEquals(0, inscripcionRepository.count());
                    assertTrue(alumnoRepository.existsById(alumno.id()));
                    assertTrue(materiaRepository.existsById(materia.id()));
                  }
                }
                """;
    }
}
