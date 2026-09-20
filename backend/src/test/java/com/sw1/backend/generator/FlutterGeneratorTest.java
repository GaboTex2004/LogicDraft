package com.sw1.backend.generator;

import com.sw1.backend.generator.flutter.FlutterGenerator;
import com.sw1.backend.generator.flutter.FlutterTypeMapper;
import com.sw1.backend.generator.schema.ApplicationCardinality;
import com.sw1.backend.generator.schema.ApplicationRelationship;
import com.sw1.backend.generator.schema.CanonicalType;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FlutterGeneratorTest {
    private final FlutterGenerator generator = new FlutterGenerator();

    @Test
    void generatesFunctionalCrudProjectStructure() {
        var project = generator.generate(GeneratorTestSchemas.serviceSchema());
        assertEquals("servicios", project.packageName());
        assertTrue(project.files().keySet().containsAll(java.util.List.of(
                "pubspec.yaml", "analysis_options.yaml", "README.md", "lib/main.dart",
                "test/widget_test.dart",
                "test/runtime_command_test.dart", "assets/voice/README.txt",
                "lib/core/config/api_config.dart", "lib/core/network/api_client.dart",
                "lib/services/runtime_ai_command_service.dart", "lib/services/voice_input_service.dart",
                "lib/models/servicio.dart", "lib/services/servicio_service.dart",
                "lib/screens/home_screen.dart", "lib/screens/servicio/servicio_list_screen.dart",
                "lib/screens/servicio/servicio_form_screen.dart", "android/README.md", "windows/README.md")));
        assertTrue(project.files().get("pubspec.yaml").contains("http: ^1.6.0"));
        assertTrue(project.files().get("pubspec.yaml").contains("record: ^6.1.2"));
        assertTrue(project.files().get("pubspec.yaml").contains("sherpa_onnx: 1.13.8"));
        assertTrue(project.files().get("lib/main.dart").contains("MaterialApp"));
        String widgetTest = project.files().get("test/widget_test.dart");
        assertTrue(widgetTest.contains("import 'package:servicios/main.dart';"));
        assertTrue(widgetTest.contains("const " + com.sw1.backend.generator.flutter.render.FlutterStaticTemplates.APPLICATION_CLASS_NAME + "()"));
        assertTrue(widgetTest.contains("La aplicacion inicia correctamente"));
        assertFalse(widgetTest.contains("find.text('0')"));
        assertFalse(widgetTest.contains("Icons.add"));
        assertFalse(widgetTest.toLowerCase().contains("counter"));
        assertTrue(project.files().get("lib/core/config/api_config.dart")
                .contains("String.fromEnvironment(\n    'API_BASE_URL'"));
        assertTrue(project.files().get("lib/core/config/api_config.dart")
                .contains("defaultValue: 'http://localhost:8080'"));
        String model = project.files().get("lib/models/servicio.dart");
        assertTrue(model.contains("factory Servicio.fromJson"));
        assertTrue(model.contains("Map<String, dynamic> toJson()"));
        assertTrue(model.contains("Map<String, dynamic> toCreateJson()"));
        assertFalse(model.substring(model.indexOf("toCreateJson"), model.indexOf("toUpdateJson")).contains("'id':"));
        String service = project.files().get("lib/services/servicio_service.dart");
        assertTrue(service.contains("static const String route = '/api/servicio'"));
        assertTrue(service.contains("Future<List<Servicio>> list()"));
        assertTrue(service.contains("Future<Servicio> create"));
        assertTrue(service.contains("Future<Servicio> update"));
        assertTrue(service.contains("Future<void> delete"));
        assertTrue(service.contains("ApiClient.post(route, request)"));
        assertTrue(service.contains("ApiClient.put('$route/$id', request)"));
        assertTrue(service.contains("ApiClient.delete('$route/$id')"));
        String form = project.files().get("lib/screens/servicio/servicio_form_screen.dart");
        assertFalse(form.contains("_idController"));
        assertTrue(form.contains("if (text.isEmpty) return 'Campo requerido'"));
        assertTrue(form.contains("double.tryParse(text)"));
        assertTrue(form.contains("Error al guardar"));
        String list = project.files().get("lib/screens/servicio/servicio_list_screen.dart");
        assertTrue(list.contains("No hay registros de Servicio."));
        assertTrue(list.contains("Registrar Servicio"));
        assertTrue(list.contains("Registro guardado correctamente"));
        assertTrue(list.contains("if (changed == true && mounted)"));
        assertTrue(list.contains("await _load()"));
        assertTrue(list.contains("¿Seguro que desea eliminar este registro?"));
        assertTrue(list.contains("tooltip: 'Editar'"));
        assertTrue(list.contains("Card("));
    }

    @Test
    void mapsCanonicalTypesAndPreservesNullable() {
        assertEquals("String", FlutterTypeMapper.dartType(CanonicalType.STRING));
        assertEquals("int", FlutterTypeMapper.dartType(CanonicalType.INTEGER));
        assertEquals("int", FlutterTypeMapper.dartType(CanonicalType.LONG));
        assertEquals("double", FlutterTypeMapper.dartType(CanonicalType.DECIMAL));
        assertEquals("bool", FlutterTypeMapper.dartType(CanonicalType.BOOLEAN));
        assertEquals("DateTime", FlutterTypeMapper.dartType(CanonicalType.DATE));
        assertEquals("DateTime", FlutterTypeMapper.dartType(CanonicalType.DATETIME));

        var optional = GeneratorTestSchemas.field("item", "Nota", CanonicalType.STRING, false, true, false);
        var entity = GeneratorTestSchemas.entity("item", "Item", CanonicalType.LONG, true, optional);
        var project = generator.generate(GeneratorTestSchemas.schema("Items", java.util.List.of(entity), java.util.List.of()));
        assertTrue(project.files().get("lib/models/item.dart").contains("final String? nota;"));
    }

    @Test
    void generatesNullSafeListsFormsAndOptionalRelations() {
        var project = generator.generate(GeneratorTestSchemas.flutterNullSafetySchema());
        String model = project.files().get("lib/models/categoria.dart");
        assertTrue(model.contains("final String? nombre;"));
        assertTrue(model.contains("final int? cantidad;"));
        assertTrue(model.contains("final double? total;"));
        assertTrue(model.contains("final bool? activa;"));
        assertTrue(model.contains("final DateTime? fecha;"));
        assertTrue(model.contains("final DateTime? actualizadaEn;"));

        String list = project.files().get("lib/screens/categoria/categoria_list_screen.dart");
        assertTrue(list.contains("title: Text(item.nombre ?? 'Categoria #${item.id}')"));
        assertFalse(list.contains("title: Text(item.nombre),"));

        String categoryForm = project.files().get("lib/screens/categoria/categoria_form_screen.dart");
        assertTrue(categoryForm.contains("bool? _activa;"));
        assertTrue(categoryForm.contains("DropdownButtonFormField<bool>"));
        assertTrue(categoryForm.contains("'activa': _activa"));
        assertTrue(categoryForm.contains("'cantidad': _cantidadController.text.trim().isEmpty ? null : int.parse"));
        assertTrue(categoryForm.contains("'total': _totalController.text.trim().isEmpty ? null : double.parse"));
        assertTrue(categoryForm.contains("'fecha': _fecha?.toIso8601String()"));
        assertTrue(categoryForm.contains("'actualizadaEn': _actualizadaEn?.toIso8601String()"));

        String cutModel = project.files().get("lib/models/corte.dart");
        assertTrue(cutModel.contains("final int? categoriaId;"));
        String cutForm = project.files().get("lib/screens/corte/corte_form_screen.dart");
        assertTrue(cutForm.contains("child: Text(item.nombre ?? 'ID ${item.id}')"));
        assertTrue(cutForm.contains("'categoriaId': _categoriaId"));
        assertFalse(cutForm.contains("'categoriaId': _categoriaId!"));
    }

    @Test
    void usesValidatedNonNullableRelationId() {
        var project = generator.generate(GeneratorTestSchemas.mainSchema());
        String form = project.files().get("lib/screens/corte/corte_form_screen.dart");
        assertTrue(form.contains("validator: (value) => value == null ? 'Selecciona una opcion' : null"));
        assertTrue(form.contains("'categoriaId': _categoriaId!"));
    }

    @Test
    void documentsWindowsAndroidAndRuntimeErrors() {
        String readme = generator.generate(GeneratorTestSchemas.serviceSchema()).files().get("README.md");
        assertTrue(readme.contains("flutter pub get"));
        assertTrue(readme.contains("flutter create --platforms=android,windows ."));
        assertTrue(readme.contains("flutter analyze"));
        assertTrue(readme.contains("flutter test"));
        assertTrue(readme.indexOf("flutter create --platforms=android,windows .") < readme.indexOf("flutter pub get"));
        assertTrue(readme.indexOf("flutter pub get") < readme.indexOf("flutter analyze"));
        assertTrue(readme.indexOf("flutter analyze") < readme.indexOf("flutter test"));
        assertTrue(readme.indexOf("flutter test") < readme.indexOf("flutter run"));
        assertTrue(readme.contains("flutter devices"));
        assertTrue(readme.contains("--dart-define=API_BASE_URL"));
        assertTrue(readme.contains("telefono Android fisico"));
        assertTrue(readme.contains("adb reverse"));
        assertTrue(readme.contains("localhost"));
        assertTrue(readme.contains("backend no disponible"));
        assertTrue(readme.contains("$env:SERVER_PORT=\"8082\""));
        assertTrue(readme.contains("flutter run -d windows --dart-define=API_BASE_URL=http://localhost:8082"));
        assertTrue(readme.contains("tienen que coincidir"));
        assertTrue(readme.contains("tiny-encoder.int8.onnx"));
        assertTrue(readme.contains("tiny-decoder.int8.onnx"));
        assertTrue(readme.contains("silero_vad.onnx"));
        assertTrue(readme.contains("tailPaddings: 300"));
        assertTrue(readme.contains("no elimina"));
        assertTrue(readme.contains("RECORD_AUDIO"));
        assertTrue(readme.toLowerCase().contains("no se envia audio"));
        assertTrue(readme.contains("validacion fisica pendiente"));
    }

    @Test
    void generatesOfflineVoiceInputWithoutDuplicatingCommandFlow() {
        var project = generator.generate(GeneratorTestSchemas.serviceSchema());
        String voice = project.files().get("lib/services/voice_input_service.dart");
        assertTrue(voice.contains("abstract interface class VoiceInputService"));
        assertTrue(voice.contains("AudioEncoder.pcm16bits"));
        assertTrue(voice.contains("OfflineWhisperModelConfig"));
        assertTrue(voice.contains("language: 'es'"));
        assertTrue(voice.contains("tailPaddings: 300"));
        assertTrue(voice.contains("decodingMethod: 'greedy_search'"));
        assertTrue(voice.contains("AudioEncoder.pcm16bits"));
        assertTrue(voice.contains("sampleRate: _sampleRate"));
        assertTrue(voice.contains("VoiceActivityDetector"));
        assertTrue(voice.contains("silero_vad.onnx"));
        assertTrue(voice.contains("minSilenceDuration: 0.8"));
        assertTrue(voice.contains("vad.flush()"));
        assertTrue(voice.contains("const padding = _sampleRate ~/ 5"));
        assertTrue(voice.contains("rootBundle.load('assets/voice/$name')"));
        assertFalse(voice.contains("http."));
        assertFalse(voice.contains("RegExp"));

        String command = project.files().get("lib/services/runtime_ai_command_service.dart");
        assertTrue(command.contains("ApiClient.post('/api/ai/commands', {'text': command})"));
        assertTrue(command.contains("if (command.isEmpty)"));

        String widget = project.files().get("lib/screens/runtime_command.dart");
        assertTrue(widget.contains("_commandService.execute(text)"));
        assertTrue(widget.contains("Transcripcion lista. Revisala antes de enviar."));
        assertTrue(widget.contains("_controller.text = _textBeforeVoice"));
        assertFalse(widget.contains("_send();\n                      final transcript"));

        String test = project.files().get("test/runtime_command_test.dart");
        assertTrue(test.contains("dicta, permite corregir y nunca envia automaticamente"));
        assertTrue(test.contains("expect(command.calls, 0)"));
        assertTrue(test.contains("cancelar conserva el texto previo"));
        assertTrue(test.contains("muestra permiso denegado sin enviar"));
        assertTrue(test.contains("transcripcion vacia no reemplaza ni envia"));
        assertTrue(test.contains("conserva exactamente numeros repetidos"));
        assertTrue(test.contains("7, 8, 1, 5, 9, 9, 9, 9"));
        assertTrue(test.contains("detiene la captura al alcanzar treinta segundos"));
        assertTrue(test.contains("doble pulsacion no crea dos solicitudes simultaneas"));
    }

    @Test
    void rendersManyToManyDateIdsAsARealMultiSelector() {
        var project = generator.generate(GeneratorTestSchemas.flutterAllTypesSchema());
        String model = project.files().get("lib/models/registro.dart");
        assertTrue(model.contains("final List<DateTime>? eventoIds;"));
        assertTrue(model.contains("DateTime.parse(item as String)"));
        assertTrue(model.contains("eventoIds?.map((item) => item.toIso8601String()).toList()"));
        String form = project.files().get("lib/screens/registro/registro_form_screen.dart");
        assertTrue(form.contains("FormField<Set<DateTime>>"));
        assertTrue(form.contains("CheckboxListTile("));
        assertTrue(form.contains("final items = await _eventoIdsService.list()"));
        assertTrue(form.contains("'eventoIds': _eventoIds.toList()"));
        assertFalse(form.contains("IDs separados por comas"));
    }

    @Test
    void generatesWorkingManyToManySelectionForCreateAndEdit() {
        var project = generator.generate(GeneratorTestSchemas.manyToManySchema());
        String model = project.files().get("lib/models/alumno.dart");
        String form = project.files().get("lib/screens/alumno/alumno_form_screen.dart");
        String list = project.files().get("lib/screens/alumno/alumno_list_screen.dart");

        assertTrue(model.contains("final List<int>? materiasIds;"));
        assertTrue(form.contains("final Set<int> _materiasIds = <int>{};"));
        assertTrue(form.contains("_materiasIds.addAll(widget.initial?.materiasIds ?? const <int>[]);"));
        assertTrue(form.contains("final items = await _materiasIdsService.list();"));
        assertTrue(form.contains("CheckboxListTile("));
        assertTrue(form.contains("value: _materiasIds.contains(id)"));
        assertTrue(form.contains("'materiasIds': _materiasIds.toList()"));
        assertTrue(list.contains("Materia IDs: ${item.materiasIds"));
        assertFalse(form.contains("IDs separados por comas"));
        assertTrue(project.files().get("README.md").contains("## Relaciones muchos a muchos"));
    }

    @Test
    void validatesOneToManyMinimumInManyToManySelector() {
        var a = GeneratorTestSchemas.entity("a", "Alpha", CanonicalType.INTEGER, true);
        var b = GeneratorTestSchemas.entity("b", "Beta", CanonicalType.INTEGER, true);
        var relation = new ApplicationRelationship("required-many", "a", "b",
                ApplicationCardinality.ZERO_MANY, ApplicationCardinality.ONE_MANY);
        var project = generator.generate(GeneratorTestSchemas.schema(
                "RequiredMany", List.of(a, b), List.of(relation)));
        String form = project.files().get("lib/screens/alpha/alpha_form_screen.dart");

        assertTrue(form.contains("value == null || value.isEmpty ? 'Selecciona al menos una opcion' : null"));
    }

    @Test
    void generatesReadableSelectorForSimpleRelationUsingNombre() {
        var project = generator.generate(GeneratorTestSchemas.mainSchema());
        String form = project.files().get("lib/screens/corte/corte_form_screen.dart");
        assertTrue(form.contains("DropdownButtonFormField<int>"));
        assertTrue(form.contains("final _categoriaIdService = const CategoriaService()"));
        assertTrue(form.contains("await _categoriaIdService.list()"));
        assertTrue(form.contains("child: Text(item.nombre)"));
        assertTrue(form.contains("value: item.id"));
        assertTrue(form.contains("'categoriaId': _categoriaId!"));
    }

    @Test
    void generatesAssociativeEntityCrudWithTwoIndependentRequiredSelectors() {
        var project = generator.generate(GeneratorTestSchemas.associativeMetadataSchema());
        String model = project.files().get("lib/models/inscripcion.dart");
        String service = project.files().get("lib/services/inscripcion_service.dart");
        String form = project.files().get("lib/screens/inscripcion/inscripcion_form_screen.dart");
        String list = project.files().get("lib/screens/inscripcion/inscripcion_list_screen.dart");

        assertTrue(model.contains("final int id;"));
        assertTrue(model.contains("final int nota;"));
        assertTrue(model.contains("final DateTime fechaInscripcion;"));
        assertTrue(model.contains("final int alumnoId;"));
        assertTrue(model.contains("final int materiaId;"), "LONG tambien se representa como int en Dart");
        assertEquals(1, occurrences(model, "final int alumnoId;"));
        assertEquals(1, occurrences(model, "final int materiaId;"));
        String createJson = model.substring(model.indexOf("toCreateJson"), model.indexOf("toUpdateJson"));
        assertTrue(createJson.contains("'alumnoId': alumnoId"));
        assertTrue(createJson.contains("'materiaId': materiaId"));
        assertTrue(createJson.contains("'nota': nota"));
        assertTrue(createJson.contains("'fechaInscripcion': fechaInscripcion.toIso8601String()"));

        assertTrue(form.contains("final _alumnoIdService = const AlumnoService()"));
        assertTrue(form.contains("final _materiaIdService = const MateriaService()"));
        assertTrue(form.contains("DropdownButtonFormField<int>"));
        assertEquals(2, occurrences(form,
                "validator: (value) => value == null ? 'Selecciona una opcion' : null"));
        assertTrue(form.contains("_alumnoIdLoading"));
        assertTrue(form.contains("_materiaIdLoading"));
        assertTrue(form.contains("No hay registros disponibles de Alumno. Crea uno antes de continuar."));
        assertTrue(form.contains("No hay registros disponibles de Materia. Crea uno antes de continuar."));
        assertTrue(form.contains("onPressed: _loadAlumnoIdOptions"));
        assertTrue(form.contains("onPressed: _loadMateriaIdOptions"));
        assertTrue(form.contains("_alumnoId = widget.initial?.alumnoId"));
        assertTrue(form.contains("_materiaId = widget.initial?.materiaId"));
        assertTrue(form.contains("'alumnoId': _alumnoId!"));
        assertTrue(form.contains("'materiaId': _materiaId!"));
        assertTrue(form.contains("error.statusCode == 409"));
        assertTrue(form.contains("key: const Key('save-error')"));
        String apiErrorHandler = form.substring(form.lastIndexOf("} on ApiException catch (error)"),
                form.indexOf("} on FormatException"));
        assertFalse(apiErrorHandler.contains("Navigator.of(context).pop"));
        assertFalse(form.contains("Controller.clear()"));
        assertFalse(form.contains("_alumnoId = null"));
        assertFalse(form.contains("_materiaId = null"));

        assertTrue(service.contains("Future<List<Inscripcion>> list()"));
        assertTrue(service.contains("Future<Inscripcion> get(Object id)"));
        assertTrue(service.contains("Future<Inscripcion> create"));
        assertTrue(service.contains("Future<Inscripcion> update"));
        assertTrue(service.contains("Future<void> delete"));
        assertTrue(list.contains("Alumno ID: ${item.alumnoId}"));
        assertTrue(list.contains("Materia ID: ${item.materiaId}"));
    }

    @Test
    void preservesNullableAndRequiredOwnFieldsOnAssociativeEntity() {
        var schema = GeneratorTestSchemas.associativeMetadataSchema();
        var base = schema.entities().stream().filter(entity -> entity.association() != null).findFirst().orElseThrow();
        var association = new com.sw1.backend.generator.schema.ApplicationEntity(
                base.id(), base.name(), base.technicalName(),
                List.of(base.fields().getFirst(),
                        GeneratorTestSchemas.field(base.id(), "Nota", CanonicalType.DECIMAL, false, true, false),
                        GeneratorTestSchemas.field(base.id(), "Observacion", CanonicalType.STRING, false, false, false)),
                base.association());
        var custom = new com.sw1.backend.generator.schema.ApplicationSchema(schema.schemaVersion(), schema.projectName(),
                schema.applicationName(), schema.technicalName(),
                List.of(schema.entities().get(0), schema.entities().get(1), association), schema.relationships());

        var project = generator.generate(custom);
        String model = project.files().get("lib/models/inscripcion.dart");
        String form = project.files().get("lib/screens/inscripcion/inscripcion_form_screen.dart");
        assertTrue(model.contains("final double? nota;"));
        assertTrue(model.contains("final String observacion;"));
        assertTrue(form.contains("'nota': _notaController.text.trim().isEmpty ? null : double.parse"));
        assertTrue(form.contains("if (text.isEmpty) return 'Campo requerido'"));
    }

    @Test
    void apiClientExplainsConflictWithoutRetryingAutomatically() {
        String client = generator.generate(GeneratorTestSchemas.associativeMetadataSchema())
                .files().get("lib/core/network/api_client.dart");
        assertTrue(client.contains("if (response.statusCode == 409)"));
        assertTrue(client.contains("relaciones duplicadas"));
        assertFalse(client.contains("retry"));
    }

    private static int occurrences(String value, String part) {
        return value.split(java.util.regex.Pattern.quote(part), -1).length - 1;
    }
}
