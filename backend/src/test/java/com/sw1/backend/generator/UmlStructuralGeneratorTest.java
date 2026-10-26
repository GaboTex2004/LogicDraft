package com.sw1.backend.generator;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sw1.backend.generator.export.FullStackGenerator;
import com.sw1.backend.generator.flutter.FlutterGenerator;
import com.sw1.backend.generator.mapper.ApplicationSchemaMapper;
import com.sw1.backend.generator.spring.SpringBootGenerator;
import com.sw1.backend.generator.validation.ApplicationSchemaException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UmlStructuralGeneratorTest {
    private final FullStackGenerator generator = new FullStackGenerator(new SpringBootGenerator(), new FlutterGenerator());
    @TempDir Path temporaryDirectory;

    @Test
    void mixedDiagramKeepsCrudAndAddsTypedJavaAndDartSkeletons() {
        Map<String, Object> document = document("Estado");
        var project = generator.generate(ApplicationSchemaMapper.fromDocument("Ventas", document));

        assertTrue(project.files().containsKey("backend/src/main/java/com/logicdraft/generated/ventas/domain/uml/Estado.java"));
        assertTrue(project.files().containsKey("backend/src/main/java/com/logicdraft/generated/ventas/domain/uml/Consultable.java"));
        assertTrue(project.files().containsKey("backend/src/main/java/com/logicdraft/generated/ventas/domain/uml/Reporte.java"));
        assertTrue(project.files().containsKey("frontend/lib/models/uml/estado.dart"));
        assertTrue(project.files().keySet().stream().anyMatch(path -> path.endsWith("entity/Cliente.java")));
        String javaClass = project.files().get("backend/src/main/java/com/logicdraft/generated/ventas/domain/uml/Reporte.java");
        assertTrue(javaClass.contains("implements Consultable"));
        assertTrue(javaClass.contains("UnsupportedOperationException"));
        assertTrue(project.files().get("backend/src/main/java/com/logicdraft/generated/ventas/domain/uml/Estado.java")
                .contains("ACTIVO"));
    }

    @Test
    void unknownStructuralTypeFailsWithElementAndType() {
        Map<String, Object> document = document("TipoInexistente");
        ApplicationSchemaException error = assertThrows(ApplicationSchemaException.class,
                () -> generator.generate(ApplicationSchemaMapper.fromDocument("Ventas", document)));
        assertTrue(error.getMessage().contains("Reporte.codigo"));
        assertTrue(error.getMessage().contains("TipoInexistente"));
    }

    @Test
    void generatedJavaDomainSkeletonsCompileInIsolation() throws Exception {
        var project = generator.generate(ApplicationSchemaMapper.fromDocument("Ventas", document("Estado")));
        var sources = project.files().entrySet().stream()
                .filter(entry -> entry.getKey().contains("/domain/uml/") && entry.getKey().endsWith(".java"))
                .toList();
        String[] paths = new String[sources.size()];
        for (int index = 0; index < sources.size(); index++) {
            Path target = temporaryDirectory.resolve(sources.get(index).getKey().substring("backend/".length()));
            Files.createDirectories(target.getParent());
            Files.writeString(target, sources.get(index).getValue());
            paths[index] = target.toString();
        }
        int result = ToolProvider.getSystemJavaCompiler().run(null, null, null, paths);
        assertTrue(result == 0, "Los esqueletos Java UML deben compilar sin dependencias externas");
    }

    private static Map<String, Object> document(String reportAttributeType) {
        Map<String, Object> entity = node("customer", "Cliente", "ENTITY", List.of(field("id", "id", "INTEGER", true)), List.of(), List.of());
        Map<String, Object> enumeration = node("state", "Estado", "ENUMERATION", List.of(), List.of(), List.of("ACTIVO", "INACTIVO"));
        Map<String, Object> contract = node("contract", "Consultable", "INTERFACE", List.of(),
                List.of(method("consultar", "Estado")), List.of());
        Map<String, Object> report = node("report", "Reporte", "CLASS",
                List.of(field("code", "codigo", reportAttributeType, false)), List.of(method("consultar", "Estado")), List.of());
        return new LinkedHashMap<>(Map.of("version", 2, "nodes", List.of(entity, enumeration, contract, report),
                "edges", List.of(edge("implements", "report", "contract", "REALIZATION"))));
    }

    private static Map<String, Object> node(String id, String name, String kind, List<Map<String, Object>> attributes,
                                            List<Map<String, Object>> methods, List<String> literals) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", id); data.put("name", name); data.put("kind", kind); data.put("attributes", attributes);
        data.put("methods", methods); data.put("literals", literals);
        return new LinkedHashMap<>(Map.of("id", id, "type", "entity", "position", Map.of("x", 0, "y", 0), "data", data));
    }

    private static Map<String, Object> field(String id, String name, String type, boolean pk) {
        return new LinkedHashMap<>(Map.of("id", id, "name", name, "type", type, "primaryKey", pk,
                "nullable", !pk, "visibility", "private", "static", false));
    }

    private static Map<String, Object> method(String name, String returnType) {
        return new LinkedHashMap<>(Map.of("id", name, "name", name, "returnType", returnType,
                "visibility", "public", "static", false, "abstract", false, "parameters", List.of()));
    }

    private static Map<String, Object> edge(String id, String source, String target, String kind) {
        return Map.of("id", id, "source", source, "target", target, "type", "relationship",
                "data", Map.of("relationKind", kind, "sourceCardinality", "ONE_ONE", "targetCardinality", "ONE_ONE"));
    }
}
