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
 * Opt-in integration against a real PostgreSQL server. It creates and drops only a random schema.
 * Enable with LOGICDRAFT_TEST_POSTGRES_URL, LOGICDRAFT_TEST_POSTGRES_USER and
 * LOGICDRAFT_TEST_POSTGRES_PASSWORD.
 */
@Tag("postgres-integration")
class GeneratedManyToManyPostgresIntegrationTest {
    @TempDir Path temporaryDirectory;

    @Test
    void generatedProjectCreatesAndPersistsARealJoinTable() throws Exception {
        String adminUrl = System.getenv("LOGICDRAFT_TEST_POSTGRES_URL");
        String user = System.getenv("LOGICDRAFT_TEST_POSTGRES_USER");
        String password = System.getenv("LOGICDRAFT_TEST_POSTGRES_PASSWORD");
        Assumptions.assumeTrue(adminUrl != null && !adminUrl.isBlank()
                && user != null && password != null,
                "Define LOGICDRAFT_TEST_POSTGRES_* para ejecutar PostgreSQL real");

        String schema = "logicdraft_nm_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        try (Connection connection = DriverManager.getConnection(adminUrl, user, password);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA \"" + schema + "\"");
        }
        try {
            Path root = materializeGeneratedProject();
            Path generatedTest = root.resolve(
                    "src/test/java/com/logicdraft/generated/academia/GeneratedManyToManyPersistenceTest.java");
            Files.createDirectories(generatedTest.getParent());
            Files.writeString(generatedTest, generatedPersistenceTest(), StandardCharsets.UTF_8);

            Path log = temporaryDirectory.resolve("generated-postgres.log");
            boolean windows = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
            ProcessBuilder builder = windows
                    ? new ProcessBuilder("cmd.exe", "/c", "mvn.cmd", "-q",
                        "-Dtest=GeneratedManyToManyPersistenceTest", "test")
                    : new ProcessBuilder("mvn", "-q", "-Dtest=GeneratedManyToManyPersistenceTest", "test");
            String separator = adminUrl.contains("?") ? "&" : "?";
            builder.environment().put("DB_URL", adminUrl + separator + "currentSchema=" + schema);
            builder.environment().put("DB_USER", user);
            builder.environment().put("DB_PASSWORD", password);
            Process process = builder.directory(root.toFile()).redirectErrorStream(true)
                    .redirectOutput(log.toFile()).start();
            boolean finished = process.waitFor(Duration.ofMinutes(3).toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) process.destroyForcibly();
            assertTrue(finished, "El test PostgreSQL generado excedio tres minutos");
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
                          AND constraint_type = 'UNIQUE'
                        """, schema));
                assertEquals(2, scalar(connection, """
                        SELECT count(*) FROM information_schema.columns
                        WHERE table_schema = ? AND table_name = 'alumno_materia'
                          AND column_name IN ('alumno_id', 'materia_id') AND data_type = 'integer'
                        """, schema));
                assertEquals(1, scalar(connection,
                        "SELECT count(*) FROM \"" + schema + "\".alumno_materia", null));
                assertEquals(2, scalar(connection,
                        "SELECT count(*) FROM \"" + schema + "\".materia", null));
            }
        } finally {
            try (Connection connection = DriverManager.getConnection(adminUrl, user, password);
                 Statement statement = connection.createStatement()) {
                statement.execute("DROP SCHEMA IF EXISTS \"" + schema + "\" CASCADE");
            }
        }
    }

    private Path materializeGeneratedProject() throws Exception {
        var generated = new SpringBootGenerator().generate(GeneratorTestSchemas.manyToManySchema());
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
                package com.logicdraft.generated.academia;

                import com.logicdraft.generated.academia.dto.*;
                import com.logicdraft.generated.academia.error.ResourceNotFoundException;
                import com.logicdraft.generated.academia.repository.MateriaRepository;
                import com.logicdraft.generated.academia.service.*;
                import java.util.Set;
                import org.junit.jupiter.api.Test;
                import org.springframework.beans.factory.annotation.Autowired;
                import org.springframework.boot.test.context.SpringBootTest;
                import static org.junit.jupiter.api.Assertions.*;

                @SpringBootTest
                class GeneratedManyToManyPersistenceTest {
                  @Autowired AlumnoService alumnoService;
                  @Autowired MateriaService materiaService;
                  @Autowired MateriaRepository materiaRepository;

                  @Test
                  void createsUpdatesAndRemovesAssociationsWithoutDeletingTargets() {
                    var matematicas = materiaService.crear(new MateriaCreateRequest("Matematicas"));
                    var fisica = materiaService.crear(new MateriaCreateRequest("Fisica"));
                    var gabriel = alumnoService.crear(new AlumnoCreateRequest(
                        "Gabriel", Set.of(matematicas.id(), fisica.id())));
                    assertEquals(Set.of(matematicas.id(), fisica.id()), gabriel.materiasIds());
                    assertEquals(gabriel.materiasIds(), alumnoService.obtenerPorId(gabriel.id()).materiasIds());

                    var updated = alumnoService.actualizar(gabriel.id(),
                        new AlumnoUpdateRequest("Gabriel", Set.of(fisica.id())));
                    assertEquals(Set.of(fisica.id()), updated.materiasIds());

                    var ana = alumnoService.crear(new AlumnoCreateRequest("Ana", Set.of(matematicas.id())));
                    var cleared = alumnoService.actualizar(ana.id(), new AlumnoUpdateRequest("Ana", Set.of()));
                    assertTrue(cleared.materiasIds().isEmpty());
                    assertEquals(2, materiaRepository.count());

                    assertThrows(ResourceNotFoundException.class, () -> alumnoService.crear(
                        new AlumnoCreateRequest("Invalido", Set.of(999999))));
                    assertEquals(2, materiaRepository.count());
                  }
                }
                """;
    }
}
