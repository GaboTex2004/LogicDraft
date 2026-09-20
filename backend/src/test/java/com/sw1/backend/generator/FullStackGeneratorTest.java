package com.sw1.backend.generator;

import com.sw1.backend.generator.export.FullStackGenerator;
import com.sw1.backend.generator.flutter.FlutterGenerator;
import com.sw1.backend.generator.spring.SpringBootGenerator;
import com.sw1.backend.generator.zip.SafeZipWriter;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FullStackGeneratorTest {
    private final FullStackGenerator generator = new FullStackGenerator(new SpringBootGenerator(), new FlutterGenerator());

    @Test
    void assemblesDeterministicBackendFrontendZip() throws Exception {
        var schema = GeneratorTestSchemas.mainSchema();
        var first = generator.generate(schema);
        var second = generator.generate(schema);
        assertEquals("peluqueria", first.rootDirectoryName());
        assertEquals("peluqueria.zip", first.downloadFileName());
        assertEquals(first.files(), second.files());
        assertTrue(first.files().containsKey("README.md"));
        assertTrue(first.files().containsKey("backend/pom.xml"));
        assertTrue(first.files().containsKey("backend/src/main/resources/application.properties"));
        assertTrue(first.files().containsKey("frontend/pubspec.yaml"));
        assertTrue(first.files().containsKey("frontend/lib/main.dart"));
        String readme = first.files().get("README.md");
        assertTrue(readme.contains("cd backend"));
        assertTrue(readme.contains("docker compose up -d"));
        assertTrue(readme.contains("cd ../frontend"));
        assertTrue(readme.contains("SERVER_PORT"));
        assertTrue(readme.contains("DB_URL"));
        assertTrue(readme.contains("API_BASE_URL=http://localhost:8082"));
        assertTrue(readme.contains("## Problemas frecuentes"));
        assertTrue(readme.contains("### Puerto 8080 ocupado"));
        assertTrue(readme.contains("$env:SERVER_PORT=\"8082\""));
        assertFalse(readme.contains("```powershell\nset SERVER_PORT"));
        assertTrue(readme.contains("ambos tienen que coincidir"));
        assertTrue(readme.contains("tabla intermedia con foreign keys y pareja UNIQUE"));

        var expectedBackend = new SpringBootGenerator().generate(schema).files();
        var actualBackend = new LinkedHashMap<String, String>();
        first.files().forEach((path, content) -> {
            if (path.startsWith("backend/")) actualBackend.put(path.substring("backend/".length()), content);
        });
        assertEquals(expectedBackend, actualBackend);
        var expectedFrontend = new FlutterGenerator().generate(schema).files();
        var actualFrontend = new LinkedHashMap<String, String>();
        first.files().forEach((path, content) -> {
            if (path.startsWith("frontend/")) actualFrontend.put(path.substring("frontend/".length()), content);
        });
        assertEquals(expectedFrontend, actualFrontend);

        byte[] zip = SafeZipWriter.write(first);
        List<String> entries = entries(zip);
        assertTrue(entries.stream().allMatch(path -> path.startsWith("peluqueria/")));
        assertTrue(entries.contains("peluqueria/backend/pom.xml"));
        assertTrue(entries.contains("peluqueria/frontend/pubspec.yaml"));
        assertTrue(entries.contains("peluqueria/README.md"));
        assertArrayEquals(zip, SafeZipWriter.write(second));
    }

    @Test
    void preservesManyToManyContractsInsideFullStackZip() throws Exception {
        var project = generator.generate(GeneratorTestSchemas.manyToManySchema());
        String entity = project.files().get(
                "backend/src/main/java/com/logicdraft/generated/academia/entity/Alumno.java");
        String form = project.files().get("frontend/lib/screens/alumno/alumno_form_screen.dart");

        assertTrue(entity.contains("@JoinTable(name = \"alumno_materia\""));
        assertTrue(entity.contains("@UniqueConstraint(name = \"uk_alumno_materia_pair\""));
        assertTrue(form.contains("FormField<Set<int>>"));
        assertTrue(form.contains("'materiasIds': _materiasIds.toList()"));

        List<String> entries = entries(SafeZipWriter.write(project));
        assertTrue(entries.contains("academia/backend/src/main/java/com/logicdraft/generated/academia/entity/Alumno.java"));
        assertTrue(entries.contains("academia/frontend/lib/screens/alumno/alumno_form_screen.dart"));
    }

    @Test
    void includesAssociativeEntityCrudInBothSidesOfFullStackZip() throws Exception {
        var project = generator.generate(GeneratorTestSchemas.associativeMetadataSchema());
        String backendRequest = project.files().get(
                "backend/src/main/java/com/logicdraft/generated/academiaasociativa/dto/InscripcionCreateRequest.java");
        String frontendModel = project.files().get("frontend/lib/models/inscripcion.dart");
        String frontendForm = project.files().get("frontend/lib/screens/inscripcion/inscripcion_form_screen.dart");

        assertTrue(backendRequest.contains("Integer alumnoId"));
        assertTrue(backendRequest.contains("Long materiaId"));
        assertTrue(frontendModel.contains("final int alumnoId;"));
        assertTrue(frontendModel.contains("final int materiaId;"));
        assertTrue(frontendForm.contains("'alumnoId': _alumnoId!"));
        assertTrue(frontendForm.contains("'materiaId': _materiaId!"));

        List<String> entries = entries(SafeZipWriter.write(project));
        assertTrue(entries.contains("academiaasociativa/backend/src/main/java/com/logicdraft/generated/academiaasociativa/entity/Inscripcion.java"));
        assertTrue(entries.contains("academiaasociativa/frontend/lib/models/inscripcion.dart"));
        assertTrue(entries.contains("academiaasociativa/frontend/lib/screens/inscripcion/inscripcion_form_screen.dart"));
    }

    private static List<String> entries(byte[] zip) throws Exception {
        List<String> result = new ArrayList<>();
        try (var input = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (var entry = input.getNextEntry(); entry != null; entry = input.getNextEntry()) result.add(entry.getName());
        }
        return result;
    }
}
