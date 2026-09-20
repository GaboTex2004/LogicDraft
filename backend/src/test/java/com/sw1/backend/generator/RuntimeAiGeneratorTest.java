package com.sw1.backend.generator;

import com.sw1.backend.generator.export.FullStackGenerator;
import com.sw1.backend.generator.flutter.FlutterGenerator;
import com.sw1.backend.generator.spring.SpringBootGenerator;
import com.sw1.backend.generator.schema.CanonicalType;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RuntimeAiGeneratorTest {
    @Test
    void generatedSpringContainsSchemaBoundRuntimeCreateBoundary() {
        var schema = GeneratorTestSchemas.mainSchema();
        var generated = new SpringBootGenerator().generate(schema);
        var repeated = new SpringBootGenerator().generate(schema);
        assertEquals(generated.files(), repeated.files(), "La generacion debe ser determinista");
        String root = "src/main/java/com/logicdraft/generated/peluqueria/";
        String controller = generated.files().get(root + "ai/RuntimeAiCommandController.java");
        assertNotNull(controller);
        assertTrue(controller.contains("@RequestMapping(\"/api/ai/commands\")"));
        assertTrue(controller.contains("/api/runtime/interpret"));
        assertTrue(controller.contains("\\\"primaryKey\\\""));
        assertTrue(controller.contains("\\\"nullable\\\""));
        assertTrue(controller.contains("case \"Categoria\" -> createCategoria"));
        assertTrue(controller.contains("categoriaService.crear(new CategoriaCreateRequest"));
        assertTrue(controller.contains("findTop2ByNombreIgnoreCase(label)"));
        assertTrue(controller.contains("!\"CREATE\".equals(result.get(\"operation\"))"));
        assertTrue(controller.contains("explicitId(text"));
        assertTrue(controller.contains("Runtime AI command received"));
        assertTrue(controller.contains("List<String> missingFields = new ArrayList<>()"));
        assertTrue(controller.contains("response.put(\"missingFields\", List.copyOf(missingFields))"));
        assertTrue(controller.contains("if (!missingFields.isEmpty()) return missingReply"));
        assertTrue(controller.contains("phoneValue(required(values, \"telefono\""));
        assertTrue(controller.contains("un telefono debe conservarse como texto"));
        assertTrue(controller.contains("no sera inventado"));
        assertTrue(controller.contains("AI service responded: {} body={}"));
        assertTrue(controller.contains("safeBody(error.getResponseBodyAsString())"));
        assertTrue(controller.contains("AI service timeout calling"));
        assertTrue(controller.contains("AI service returned invalid JSON/contract"));
        assertTrue(controller.contains("<redacted>"));
        String handler = generated.files().get(root + "error/GlobalExceptionHandler.java");
        assertTrue(handler.contains("@ExceptionHandler(ResponseStatusException.class)"));
        assertTrue(handler.contains("response(exception.getStatusCode(), message)"));
        assertFalse(controller.contains("Statement.execute"));
        assertTrue(generated.files().get(root + "repository/CategoriaRepository.java")
                .contains("findTop2ByNombreIgnoreCase(String nombre)"));
    }

    @Test
    void generatedFullstackDocumentsSeparateAiServiceAndFlutterEntry() {
        var schema = GeneratorTestSchemas.mainSchema();
        var spring = new SpringBootGenerator().generate(schema);
        var flutter = new FlutterGenerator().generate(schema);
        assertTrue(spring.files().get("src/main/resources/application.properties")
                .contains("ai.service.url=${AI_SERVICE_URL:http://localhost:8000}"));
        assertTrue(spring.files().get(".env.example").contains("AI_SERVICE_URL=http://localhost:8000"));
        assertTrue(spring.files().get("README.md").contains("categoriaId=7"));
        assertTrue(flutter.files().get("lib/screens/home_screen.dart").contains("const RuntimeCommand()"));
        assertTrue(flutter.files().get("lib/screens/runtime_command.dart")
                .contains("final result = await _commandService.execute(text)"));
        assertTrue(flutter.files().get("lib/services/runtime_ai_command_service.dart")
                .contains("ApiClient.post('/api/ai/commands', {'text': command})"));
        String apiClient = flutter.files().get("lib/core/network/api_client.dart");
        assertTrue(apiClient.contains("final message = decoded['message'] ?? decoded['detail']"));
        assertTrue(apiClient.contains("throw ApiException(_errorMessage(response), response.statusCode)"));
        assertTrue(new FullStackGenerator(new SpringBootGenerator(), new FlutterGenerator())
                .generate(schema).files().get("README.md").contains("AI_SERVICE_URL"));
    }

    @Test
    void numericPhoneSchemaIsNotSilentlyCoercedOrNormalized() {
        var personal = GeneratorTestSchemas.entity("personal", "Personal", CanonicalType.INTEGER, true,
                GeneratorTestSchemas.field("personal", "Telefono", CanonicalType.INTEGER, false, true, false));
        var schema = GeneratorTestSchemas.schema("Barbero", List.of(personal), List.of());
        String controller = new SpringBootGenerator().generate(schema).files().entrySet().stream()
                .filter(entry -> entry.getKey().endsWith("RuntimeAiCommandController.java"))
                .findFirst().orElseThrow().getValue();
        assertTrue(controller.contains("if (values.get(\"telefono\") != null)"));
        assertTrue(controller.contains("debe estar definido como STRING para conservar el telefono"));
    }

    @Test
    void generatedRuntimeResolvesManyToManyNamesWithoutInventingIds() {
        var generated = new SpringBootGenerator().generate(GeneratorTestSchemas.manyToManySchema());
        String controller = generated.files().entrySet().stream()
                .filter(entry -> entry.getKey().endsWith("RuntimeAiCommandController.java"))
                .findFirst().orElseThrow().getValue();
        assertTrue(controller.contains("\\\"multiple\\\":true"));
        assertTrue(controller.contains("instanceof List<?> labels"));
        assertTrue(controller.contains("Set<Integer> materiasIdsValue = new LinkedHashSet<>()"));
        assertTrue(controller.contains("materiaRepository.findTop2ByNombreIgnoreCase(label)"));
        assertTrue(controller.contains("El nombre relacionado no aparece en la instruccion"));
        assertTrue(controller.contains("No existe Materia con nombre"));
        assertTrue(controller.contains("Hay varias coincidencias"));
        assertTrue(controller.contains("usa nombres de registros existentes, no IDs inferidos"));
        assertFalse(controller.contains("no admite relaciones multiples"));
    }
}
