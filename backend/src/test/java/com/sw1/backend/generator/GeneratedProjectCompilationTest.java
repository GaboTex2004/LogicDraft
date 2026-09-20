package com.sw1.backend.generator;

import com.sw1.backend.generator.spring.GeneratedProject;
import com.sw1.backend.generator.spring.SpringBootGenerator;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.List;
import com.sw1.backend.generator.schema.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

class GeneratedProjectCompilationTest {
    @TempDir Path temporaryDirectory;

    @Test
    void generatedMainExampleCompilesWithMaven() throws Exception {
        compile(new SpringBootGenerator().generate(GeneratorTestSchemas.mainSchema()), "main", true);
    }

    @Test
    void generatedOtherRelationshipFamiliesCompileWithMaven() throws Exception {
        var alpha = GeneratorTestSchemas.entity("a", "Alpha", CanonicalType.INTEGER, true);
        var beta = GeneratorTestSchemas.entity("b", "Beta", CanonicalType.INTEGER, true);
        var gamma = GeneratorTestSchemas.entity("c", "Gamma", CanonicalType.INTEGER, true);
        var delta = GeneratorTestSchemas.entity("d", "Delta", CanonicalType.INTEGER, true);
        var relations = List.of(
                new ApplicationRelationship("one", "a", "b", ApplicationCardinality.ZERO_ONE, ApplicationCardinality.ONE_ONE),
                new ApplicationRelationship("reverse", "a", "c", ApplicationCardinality.ZERO_MANY, ApplicationCardinality.ONE_ONE),
                new ApplicationRelationship("many", "c", "d", ApplicationCardinality.ZERO_MANY, ApplicationCardinality.ONE_MANY));
        compile(new SpringBootGenerator().generate(GeneratorTestSchemas.schema(
                "Relations", List.of(alpha, beta, gamma, delta), relations)), "relations", false);
    }

    @Test
    void generatedNamedManyToManyProjectCompilesWithMaven() throws Exception {
        compile(new SpringBootGenerator().generate(GeneratorTestSchemas.manyToManySchema()), "many-to-many", false);
    }

    @Test
    void generatedAssociativeEntityProjectCompilesWithMaven() throws Exception {
        compile(new SpringBootGenerator().generate(GeneratorTestSchemas.associativeMetadataSchema()),
                "associative-entity", false);
    }

    private void compile(GeneratedProject generated, String directory, boolean runtimeTests) throws Exception {
        Path root = temporaryDirectory.resolve(directory).resolve(generated.rootDirectoryName());
        for (var file : generated.files().entrySet()) {
            Path target = root.resolve(file.getKey()).normalize();
            assertTrue(target.startsWith(root));
            Files.createDirectories(target.getParent());
            Files.writeString(target, file.getValue(), StandardCharsets.UTF_8);
        }
        if (runtimeTests) {
            Path target = root.resolve("src/test/java/com/logicdraft/generated/peluqueria/ai/RuntimeAiCommandControllerTest.java");
            Files.createDirectories(target.getParent());
            try (var source = getClass().getResourceAsStream("/generator/RuntimeAiCommandControllerTest.java.txt")) {
                assertNotNull(source);
                Files.copy(source, target);
            }
        }
        Path log = temporaryDirectory.resolve(directory + "-maven.log");
        boolean windows = System.getProperty("os.name").toLowerCase().contains("win");
        ProcessBuilder builder = windows
                ? new ProcessBuilder("cmd.exe", "/c", "mvn.cmd", "clean", "test")
                : new ProcessBuilder("mvn", "clean", "test");
        Process process = builder.directory(root.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        boolean finished = process.waitFor(Duration.ofMinutes(2).toMillis(), TimeUnit.MILLISECONDS);
        if (!finished) process.destroyForcibly();
        assertTrue(finished, "La compilacion Maven del proyecto generado excedio 2 minutos");
        assertEquals(0, process.exitValue(), () -> {
            try { return Files.readString(log); } catch (Exception ignored) { return "No se pudo leer el log"; }
        });
    }
}
