package com.sw1.backend.generator.flutter.render;

import com.sw1.backend.generator.schema.ApplicationEntity;
import com.sw1.backend.generator.schema.ApplicationSchema;
import com.sw1.backend.generator.spring.SpringNames;
import java.util.stream.Collectors;

public final class FlutterStaticTemplates {
    public static final String HTTP_VERSION = "1.6.0";
    public static final String RECORD_VERSION = "6.1.2";
    public static final String SHERPA_ONNX_VERSION = "1.13.8";
    public static final String PATH_PROVIDER_VERSION = "2.1.5";
    public static final String APPLICATION_CLASS_NAME = "GeneratedApp";

    private FlutterStaticTemplates() {
    }

    public static String pubspec(String packageName) {
        return """
                name: %s
                description: Frontend Flutter generado deterministicamente por LogicDraft.
                publish_to: none
                version: 1.0.0+1

                environment:
                  sdk: '>=3.4.0 <4.0.0'

                dependencies:
                  flutter:
                    sdk: flutter
                  http: ^%s
                  path_provider: ^%s
                  record: ^%s
                  sherpa_onnx: %s

                dev_dependencies:
                  flutter_test:
                    sdk: flutter

                flutter:
                  uses-material-design: true
                  assets:
                    - assets/voice/
                """.formatted(packageName, HTTP_VERSION, PATH_PROVIDER_VERSION, RECORD_VERSION, SHERPA_ONNX_VERSION);
    }

    public static String analysisOptions() {
        return """
                analyzer:
                  language:
                    strict-casts: true
                    strict-inference: true
                    strict-raw-types: true
                linter:
                  rules:
                    - avoid_print
                    - prefer_const_constructors
                    - use_build_context_synchronously
                """;
    }

    public static String apiConfig() {
        return """
                class ApiConfig {
                  const ApiConfig._();

                  static const String baseUrl = String.fromEnvironment(
                    'API_BASE_URL',
                    defaultValue: 'http://localhost:8080',
                  );
                }
                """;
    }

    public static String apiClient() {
        return """
                import 'dart:convert';

                import 'package:http/http.dart' as http;

                import '../config/api_config.dart';

                class ApiException implements Exception {
                  final String message;
                  final int? statusCode;

                  const ApiException(this.message, [this.statusCode]);

                  @override
                  String toString() => message;
                }

                class ApiClient {
                  const ApiClient._();

                  static Uri _uri(String path) => Uri.parse('${ApiConfig.baseUrl}$path');

                  static Future<List<Map<String, dynamic>>> getList(String path) async {
                    final decoded = await _request(() => http.get(_uri(path)));
                    if (decoded is! List<dynamic>) throw const ApiException('El backend devolvio una lista JSON inesperada.');
                    try {
                      return decoded.map((item) => Map<String, dynamic>.from(item as Map)).toList();
                    } on TypeError {
                      throw const ApiException('El backend devolvio elementos JSON invalidos.');
                    }
                  }

                  static Future<Map<String, dynamic>> getObject(String path) async =>
                      _asObject(await _request(() => http.get(_uri(path))));

                  static Future<Map<String, dynamic>> post(String path, Map<String, dynamic> body) async =>
                      _asObject(await _request(() => http.post(_uri(path), headers: _headers, body: jsonEncode(body))));

                  static Future<Map<String, dynamic>> put(String path, Map<String, dynamic> body) async =>
                      _asObject(await _request(() => http.put(_uri(path), headers: _headers, body: jsonEncode(body))));

                  static Future<void> delete(String path) async {
                    await _request(() => http.delete(_uri(path)), allowEmpty: true);
                  }

                  static const _headers = {'Content-Type': 'application/json'};

                  static Future<dynamic> _request(Future<http.Response> Function() send, {bool allowEmpty = false}) async {
                    final http.Response response;
                    try {
                      response = await send();
                    } on http.ClientException {
                      throw const ApiException('El backend no esta disponible. Comprueba la URL y que Spring este iniciado.');
                    }
                    if (response.statusCode < 200 || response.statusCode >= 300) {
                      throw ApiException(_errorMessage(response), response.statusCode);
                    }
                    if (allowEmpty && response.body.isEmpty) return null;
                    try {
                      return jsonDecode(response.body);
                    } on FormatException {
                      throw const ApiException('El backend devolvio JSON invalido o inesperado.');
                    }
                  }

                  static Map<String, dynamic> _asObject(dynamic value) {
                    if (value is! Map) throw const ApiException('El backend devolvio un objeto JSON inesperado.');
                    return Map<String, dynamic>.from(value);
                  }

                  static String _errorMessage(http.Response response) {
                    try {
                      final decoded = jsonDecode(response.body);
                      if (decoded is Map) {
                        final message = decoded['message'] ?? decoded['detail'];
                        if (message is String && message.trim().isNotEmpty) return message.trim();
                      }
                    } on FormatException {
                      // El fallback conserva un mensaje util si el backend no envio JSON.
                    }
                    if (response.statusCode == 409) {
                      return 'El registro entra en conflicto con datos existentes. Revisa valores unicos o relaciones duplicadas.';
                    }
                    return 'El backend respondio con HTTP ${response.statusCode}.';
                  }
                }
                """;
    }

    public static String main(String packageName) {
        return """
                import 'package:flutter/material.dart';

                import 'screens/home_screen.dart';

                void main() => runApp(const %s());

                class %s extends StatelessWidget {
                  const %s({super.key});

                  @override
                  Widget build(BuildContext context) => MaterialApp(
                    title: '%s',
                    debugShowCheckedModeBanner: false,
                    theme: ThemeData(colorSchemeSeed: Colors.indigo, useMaterial3: true),
                    home: const HomeScreen(),
                  );
                }
                """.formatted(APPLICATION_CLASS_NAME, APPLICATION_CLASS_NAME, APPLICATION_CLASS_NAME, packageName);
    }

    public static String runtimeAiCommandService() {
        return """
                import '../core/network/api_client.dart';

                abstract interface class RuntimeAiCommandService {
                  Future<Map<String, dynamic>> execute(String text);
                }

                class RuntimeAiCommandException implements Exception {
                  const RuntimeAiCommandException(this.message);

                  final String message;
                }

                class HttpRuntimeAiCommandService implements RuntimeAiCommandService {
                  const HttpRuntimeAiCommandService();

                  @override
                  Future<Map<String, dynamic>> execute(String text) async {
                    final command = text.trim();
                    if (command.isEmpty) {
                      throw const RuntimeAiCommandException('Escribe o dicta una instruccion antes de enviarla.');
                    }
                    try {
                      return await ApiClient.post('/api/ai/commands', {'text': command});
                    } on ApiException catch (error) {
                      throw RuntimeAiCommandException(error.message);
                    }
                  }
                }
                """;
    }

    public static String voiceInputService() {
        return """
                import 'dart:async';
                import 'dart:io';
                import 'dart:typed_data';

                import 'package:flutter/foundation.dart';
                import 'package:flutter/services.dart';
                import 'package:path_provider/path_provider.dart';
                import 'package:record/record.dart';
                import 'package:sherpa_onnx/sherpa_onnx.dart' as sherpa_onnx;

                abstract interface class VoiceInputService {
                  Future<void> start();
                  Future<String> stop();
                  Future<void> cancel();
                  Future<void> dispose();
                }

                class VoiceInputException implements Exception {
                  const VoiceInputException(this.message);

                  final String message;
                }

                class SherpaVoiceInputService implements VoiceInputService {
                  static const _sampleRate = 16000;
                  static const _vadWindowSize = 512;
                  static const _modelFiles = <String>[
                    'tiny-encoder.int8.onnx',
                    'tiny-decoder.int8.onnx',
                    'tiny-tokens.txt',
                    'silero_vad.onnx',
                  ];

                  final AudioRecorder _recorder = AudioRecorder();
                  final BytesBuilder _audio = BytesBuilder(copy: false);
                  StreamSubscription<Uint8List>? _audioSubscription;
                  Completer<void>? _streamCompleted;
                  sherpa_onnx.OfflineRecognizer? _recognizer;
                  sherpa_onnx.VoiceActivityDetector? _vad;
                  bool _bindingsInitialized = false;
                  bool _listening = false;

                  @override
                  Future<void> start() async {
                    if (_listening) return;
                    if (!Platform.isWindows && !Platform.isAndroid) {
                      throw const VoiceInputException('La entrada de voz local esta disponible solo en Windows y Android.');
                    }
                    try {
                      if (!await _recorder.hasPermission()) {
                        throw const VoiceInputException('Permiso de microfono denegado. Habilitalo en la configuracion del sistema.');
                      }
                      if (!await _recorder.isEncoderSupported(AudioEncoder.pcm16bits)) {
                        throw const VoiceInputException('El microfono no admite audio PCM de 16 bits en este dispositivo.');
                      }
                      final devices = await _recorder.listInputDevices();
                      if (devices.isEmpty) {
                        throw const VoiceInputException('No se encontro un microfono disponible.');
                      }
                      await _prepareRecognizer();
                      _audio.clear();
                      _streamCompleted = Completer<void>();
                      final stream = await _recorder.startStream(const RecordConfig(
                        encoder: AudioEncoder.pcm16bits,
                        sampleRate: _sampleRate,
                        numChannels: 1,
                      ));
                      _audioSubscription = stream.listen(
                        _audio.add,
                        onError: (Object error, StackTrace stackTrace) {
                          if (!(_streamCompleted?.isCompleted ?? true)) {
                            _streamCompleted?.completeError(error, stackTrace);
                          }
                        },
                        onDone: () {
                          if (!(_streamCompleted?.isCompleted ?? true)) _streamCompleted?.complete();
                        },
                      );
                      _listening = true;
                    } on VoiceInputException {
                      rethrow;
                    } on FlutterError {
                      throw const VoiceInputException(
                        'No se pudo cargar el modelo local. Instala Whisper y Silero VAD en assets/voice/.',
                      );
                    } on Exception {
                      throw const VoiceInputException('No se pudo iniciar el microfono o el modelo de voz local.');
                    }
                  }

                  @override
                  Future<String> stop() async {
                    if (!_listening) return '';
                    _listening = false;
                    try {
                      await _recorder.stop();
                      await _streamCompleted?.future.timeout(const Duration(seconds: 5));
                      await _audioSubscription?.cancel();
                      final bytes = _audio.takeBytes();
                      if (bytes.length < 2) return '';
                      final samples = _pcm16ToFloat32(bytes);
                      final speech = _speechOnly(samples);
                      if (speech.isEmpty) return '';
                      return _transcribe(speech);
                    } on VoiceInputException {
                      rethrow;
                    } on Exception {
                      throw const VoiceInputException('No fue posible transcribir el audio capturado.');
                    } finally {
                      _audioSubscription = null;
                      _streamCompleted = null;
                    }
                  }

                  @override
                  Future<void> cancel() async {
                    if (!_listening) return;
                    _listening = false;
                    try {
                      await _recorder.cancel();
                      await _audioSubscription?.cancel();
                      _audio.clear();
                    } on Exception {
                      throw const VoiceInputException('No fue posible cancelar la captura de voz.');
                    } finally {
                      _audioSubscription = null;
                      _streamCompleted = null;
                    }
                  }

                  Future<void> _prepareRecognizer() async {
                    if (_recognizer != null) return;
                    if (!_bindingsInitialized) {
                      await sherpa_onnx.initBindingsAsync();
                      _bindingsInitialized = true;
                    }
                    final directory = Directory('${(await getApplicationSupportDirectory()).path}/voice-model');
                    await directory.create(recursive: true);
                    final paths = <String>[];
                    for (final name in _modelFiles) {
                      final target = File('${directory.path}/$name');
                      if (!await target.exists() || await target.length() == 0) {
                        final data = await rootBundle.load('assets/voice/$name');
                        await target.writeAsBytes(
                          data.buffer.asUint8List(data.offsetInBytes, data.lengthInBytes),
                          flush: true,
                        );
                      }
                      paths.add(target.path);
                    }
                    final model = sherpa_onnx.OfflineModelConfig(
                      whisper: sherpa_onnx.OfflineWhisperModelConfig(
                        encoder: paths[0],
                        decoder: paths[1],
                        language: 'es',
                        task: 'transcribe',
                        tailPaddings: 300,
                      ),
                      tokens: paths[2],
                      modelType: 'whisper',
                      numThreads: 2,
                    );
                    _recognizer = sherpa_onnx.OfflineRecognizer(
                      sherpa_onnx.OfflineRecognizerConfig(
                        model: model,
                        decodingMethod: 'greedy_search',
                      ),
                    );
                    _vad = sherpa_onnx.VoiceActivityDetector(
                      config: sherpa_onnx.VadModelConfig(
                        sileroVad: sherpa_onnx.SileroVadModelConfig(
                          model: paths[3],
                          threshold: 0.5,
                          minSilenceDuration: 0.8,
                          minSpeechDuration: 0.25,
                          windowSize: _vadWindowSize,
                          maxSpeechDuration: 30.0,
                        ),
                        sampleRate: _sampleRate,
                        numThreads: 1,
                        debug: false,
                      ),
                      bufferSizeInSeconds: 35,
                    );
                  }

                  Float32List _speechOnly(Float32List samples) {
                    final vad = _vad;
                    if (vad == null) {
                      throw const VoiceInputException('El detector local de voz no esta cargado.');
                    }
                    vad.reset();
                    for (var offset = 0; offset < samples.length; offset += _vadWindowSize) {
                      final remaining = samples.length - offset;
                      final length = remaining < _vadWindowSize ? remaining : _vadWindowSize;
                      if (length == _vadWindowSize) {
                        vad.acceptWaveform(Float32List.sublistView(samples, offset, offset + length));
                      } else {
                        final padded = Float32List(_vadWindowSize)..setRange(0, length, samples, offset);
                        vad.acceptWaveform(padded);
                      }
                    }
                    vad.flush();
                    int? firstSpeechSample;
                    int? lastSpeechSample;
                    while (!vad.isEmpty()) {
                      final segment = vad.front();
                      firstSpeechSample ??= segment.start;
                      final segmentEnd = segment.start + segment.samples.length;
                      if (lastSpeechSample == null || segmentEnd > lastSpeechSample) lastSpeechSample = segmentEnd;
                      vad.pop();
                    }
                    if (firstSpeechSample == null || lastSpeechSample == null) return Float32List(0);
                    // Conserva 200 ms alrededor de la voz para no cortar fonemas o digitos breves.
                    const padding = _sampleRate ~/ 5;
                    final unboundedStart = firstSpeechSample - padding;
                    final start = unboundedStart > 0 ? unboundedStart : 0;
                    final unboundedEnd = lastSpeechSample + padding;
                    final end = unboundedEnd < samples.length ? unboundedEnd : samples.length;
                    return Float32List.fromList(samples.sublist(start, end));
                  }

                  String _transcribe(Float32List samples) {
                    final recognizer = _recognizer;
                    if (recognizer == null) {
                      throw const VoiceInputException('El modelo local de voz no esta cargado.');
                    }
                    final stream = recognizer.createStream();
                    try {
                      stream.acceptWaveform(samples: samples, sampleRate: _sampleRate);
                      recognizer.decode(stream);
                      return recognizer.getResult(stream).text.trim();
                    } finally {
                      stream.free();
                    }
                  }

                  Float32List _pcm16ToFloat32(Uint8List bytes) {
                    final sampleCount = bytes.length ~/ 2;
                    final samples = Float32List(sampleCount);
                    final data = ByteData.sublistView(bytes);
                    for (var index = 0; index < sampleCount; index++) {
                      samples[index] = data.getInt16(index * 2, Endian.little) / 32768.0;
                    }
                    return samples;
                  }

                  @override
                  Future<void> dispose() async {
                    if (_listening) await _recorder.cancel();
                    await _audioSubscription?.cancel();
                    await _recorder.dispose();
                    _vad?.free();
                    _vad = null;
                    _recognizer?.free();
                    _recognizer = null;
                  }
                }
                """;
    }

    public static String widgetTest(String packageName, String projectName) {
        return """
                import 'package:flutter_test/flutter_test.dart';
                import 'package:%s/main.dart';

                void main() {
                  testWidgets('La aplicacion inicia correctamente', (tester) async {
                    await tester.pumpWidget(const %s());

                    expect(find.text('%s'), findsOneWidget);
                  });
                }
                """.formatted(packageName, APPLICATION_CLASS_NAME, projectName.replace("\\", "\\\\").replace("'", "\\'"));
    }

    public static String runtimeCommandTest(String packageName) {
        return """
                import 'dart:async';

                import 'package:flutter/material.dart';
                import 'package:flutter_test/flutter_test.dart';
                import 'package:%s/screens/runtime_command.dart';
                import 'package:%s/services/runtime_ai_command_service.dart';
                import 'package:%s/services/voice_input_service.dart';

                class FakeVoiceInputService implements VoiceInputService {
                  FakeVoiceInputService({this.transcript = 'registra un corte a 25 Bs', this.startError});

                  final String transcript;
                  final VoiceInputException? startError;
                  int starts = 0;
                  int stops = 0;
                  int cancels = 0;

                  @override
                  Future<void> start() async {
                    starts++;
                    final error = startError;
                    if (error != null) throw error;
                  }

                  @override
                  Future<String> stop() async { stops++; return transcript; }

                  @override
                  Future<void> cancel() async { cancels++; }

                  @override
                  Future<void> dispose() async {}
                }

                class FakeCommandService implements RuntimeAiCommandService {
                  FakeCommandService({this.status = 'EXECUTED', this.waitFor});

                  final String status;
                  final Future<void>? waitFor;
                  int calls = 0;
                  String? lastText;

                  @override
                  Future<Map<String, dynamic>> execute(String text) async {
                    calls++;
                    lastText = text;
                    final pending = waitFor;
                    if (pending != null) await pending;
                    return {'status': status, 'message': status == 'EXECUTED' ? 'Guardado' : 'Revisa el comando'};
                  }
                }

                Widget subject(FakeVoiceInputService voice, FakeCommandService command) => MaterialApp(
                  home: Scaffold(body: RuntimeCommand(voiceInput: voice, commandService: command)),
                );

                void main() {
                  testWidgets('dicta, permite corregir y nunca envia automaticamente', (tester) async {
                    final voice = FakeVoiceInputService();
                    final command = FakeCommandService();
                    await tester.pumpWidget(subject(voice, command));

                    await tester.tap(find.byKey(const Key('voice-start')));
                    await tester.pump();
                    expect(find.text('Escuchando...'), findsOneWidget);
                    await tester.tap(find.byKey(const Key('voice-stop')));
                    await tester.pumpAndSettle();

                    expect(find.text('registra un corte a 25 Bs'), findsOneWidget);
                    expect(command.calls, 0);
                    await tester.enterText(find.byType(TextField), 'registra un corte degradado a 25 Bs');
                    expect(command.calls, 0);
                    await tester.tap(find.byKey(const Key('command-send')));
                    await tester.pumpAndSettle();
                    expect(command.calls, 1);
                    expect(command.lastText, 'registra un corte degradado a 25 Bs');
                  });

                  testWidgets('cancelar conserva el texto previo', (tester) async {
                    final voice = FakeVoiceInputService();
                    final command = FakeCommandService();
                    await tester.pumpWidget(subject(voice, command));
                    await tester.enterText(find.byType(TextField), 'texto previo');
                    await tester.tap(find.byKey(const Key('voice-start')));
                    await tester.pump();
                    await tester.tap(find.byKey(const Key('voice-cancel')));
                    await tester.pumpAndSettle();

                    expect(find.text('texto previo'), findsOneWidget);
                    expect(voice.cancels, 1);
                    expect(command.calls, 0);
                  });

                  testWidgets('muestra permiso denegado sin enviar', (tester) async {
                    final voice = FakeVoiceInputService(
                      startError: const VoiceInputException('Permiso de microfono denegado.'),
                    );
                    final command = FakeCommandService();
                    await tester.pumpWidget(subject(voice, command));
                    await tester.tap(find.byKey(const Key('voice-start')));
                    await tester.pumpAndSettle();

                    expect(find.text('Permiso de microfono denegado.'), findsOneWidget);
                    expect(command.calls, 0);
                  });

                  testWidgets('transcripcion vacia no reemplaza ni envia', (tester) async {
                    final voice = FakeVoiceInputService(transcript: '');
                    final command = FakeCommandService();
                    await tester.pumpWidget(subject(voice, command));
                    await tester.enterText(find.byType(TextField), 'borrador');
                    await tester.tap(find.byKey(const Key('voice-start')));
                    await tester.pump();
                    await tester.tap(find.byKey(const Key('voice-stop')));
                    await tester.pumpAndSettle();

                    expect(find.text('borrador'), findsOneWidget);
                    expect(find.textContaining('No se detecto voz'), findsOneWidget);
                    expect(command.calls, 0);
                  });

                  testWidgets('conserva exactamente numeros repetidos y la transcripcion no confirmada', (tester) async {
                    const dictated = 'Registra un Personal con numero 7, 8, 1, 5, 9, 9, 9, 9';
                    final voice = FakeVoiceInputService(transcript: dictated);
                    final command = FakeCommandService(status: 'NEEDS_CLARIFICATION');
                    await tester.pumpWidget(subject(voice, command));
                    await tester.tap(find.byKey(const Key('voice-start')));
                    await tester.pump();
                    await tester.tap(find.byKey(const Key('voice-stop')));
                    await tester.pumpAndSettle();

                    expect(find.text(dictated), findsOneWidget);
                    expect(command.calls, 0);
                    await tester.tap(find.byKey(const Key('command-send')));
                    await tester.pumpAndSettle();
                    expect(command.lastText, dictated);
                    expect(find.text(dictated), findsOneWidget);
                  });

                  testWidgets('doble pulsacion no crea dos solicitudes simultaneas', (tester) async {
                    final gate = Completer<void>();
                    final voice = FakeVoiceInputService();
                    final command = FakeCommandService(waitFor: gate.future);
                    await tester.pumpWidget(subject(voice, command));
                    await tester.enterText(find.byType(TextField), 'registra una categoria llamada Cabello');

                    await tester.tap(find.byKey(const Key('command-send')));
                    await tester.tap(find.byKey(const Key('command-send')));
                    await tester.pump();
                    expect(command.calls, 1);
                    gate.complete();
                    await tester.pumpAndSettle();
                    expect(command.calls, 1);
                  });

                  testWidgets('detiene la captura al alcanzar treinta segundos sin enviar', (tester) async {
                    final voice = FakeVoiceInputService(transcript: 'comando dentro del limite');
                    final command = FakeCommandService();
                    await tester.pumpWidget(subject(voice, command));
                    await tester.tap(find.byKey(const Key('voice-start')));
                    await tester.pump();
                    await tester.pump(const Duration(seconds: 30));
                    await tester.pumpAndSettle();

                    expect(voice.stops, 1);
                    expect(find.text('comando dentro del limite'), findsOneWidget);
                    expect(command.calls, 0);
                  });
                }
                """.formatted(packageName, packageName, packageName);
    }

    public static String voiceModelReadme() {
        return """
                Coloca aqui, antes de compilar, los archivos de Whisper Tiny multilingue y Silero VAD:

                - tiny-encoder.int8.onnx
                - tiny-decoder.int8.onnx
                - tiny-tokens.txt
                - silero_vad.onnx

                Consulta el README principal para la descarga oficial, tamanos, licencias y prueba segura.
                Este archivo mantiene el directorio en el ZIP; no es un modelo ni habilita reconocimiento por si solo.
                """;
    }

    public static String home(ApplicationSchema schema) {
        String imports = schema.entities().stream()
                .map(entity -> "import '" + SpringNames.sqlName(entity.technicalName(), "archivo Flutter")
                        + "/" + SpringNames.sqlName(entity.technicalName(), "archivo Flutter") + "_list_screen.dart';")
                .collect(Collectors.joining("\n"));
        String tiles = schema.entities().stream().map(FlutterStaticTemplates::tile).collect(Collectors.joining("\n"));
        return """
                import 'package:flutter/material.dart';
                import 'runtime_command.dart';

                %s

                class HomeScreen extends StatelessWidget {
                  const HomeScreen({super.key});

                  @override
                  Widget build(BuildContext context) => Scaffold(
                    appBar: AppBar(title: const Text('%s')),
                    body: LayoutBuilder(
                      builder: (context, constraints) => Center(
                        child: ConstrainedBox(
                          constraints: const BoxConstraints(maxWidth: 960),
                          child: ListView(
                            padding: EdgeInsets.symmetric(
                              horizontal: constraints.maxWidth < 600 ? 16 : 24,
                              vertical: 16,
                            ),
                            children: [
                              const RuntimeCommand(),
                              const SizedBox(height: 20),
                              const Text('Entidades', style: TextStyle(fontSize: 22, fontWeight: FontWeight.bold)),
                              const SizedBox(height: 12),
                %s
                            ],
                          ),
                        ),
                      ),
                    ),
                  );
                }
                """.formatted(imports, schema.projectName(), tiles);
    }

    public static String runtimeCommand() {
        return """
                import 'dart:async';

                import 'package:flutter/material.dart';

                import '../services/runtime_ai_command_service.dart';
                import '../services/voice_input_service.dart';

                class RuntimeCommand extends StatefulWidget {
                  const RuntimeCommand({super.key, this.commandService, this.voiceInput});

                  final RuntimeAiCommandService? commandService;
                  final VoiceInputService? voiceInput;

                  @override
                  State<RuntimeCommand> createState() => _RuntimeCommandState();
                }

                class _RuntimeCommandState extends State<RuntimeCommand> {
                  static const _maximumVoiceDuration = Duration(seconds: 30);
                  final _controller = TextEditingController();
                  late final RuntimeAiCommandService _commandService;
                  late final VoiceInputService _voiceInput;
                  bool _loading = false;
                  bool _listening = false;
                  bool _processingVoice = false;
                  String? _message;
                  String _textBeforeVoice = '';
                  Timer? _voiceLimitTimer;

                  @override
                  void initState() {
                    super.initState();
                    _commandService = widget.commandService ?? const HttpRuntimeAiCommandService();
                    _voiceInput = widget.voiceInput ?? SherpaVoiceInputService();
                  }

                  @override
                  void dispose() {
                    _voiceLimitTimer?.cancel();
                    _controller.dispose();
                    unawaited(_voiceInput.dispose());
                    super.dispose();
                  }

                  Future<void> _startVoice() async {
                    if (_loading || _listening || _processingVoice) return;
                    _textBeforeVoice = _controller.text;
                    setState(() => _message = null);
                    try {
                      await _voiceInput.start();
                      if (mounted) {
                        setState(() => _listening = true);
                        _voiceLimitTimer = Timer(_maximumVoiceDuration, () => unawaited(_stopVoice()));
                      }
                    } on VoiceInputException catch (error) {
                      if (mounted) setState(() => _message = error.message);
                    }
                  }

                  Future<void> _stopVoice() async {
                    if (!_listening || _processingVoice) return;
                    _voiceLimitTimer?.cancel();
                    _voiceLimitTimer = null;
                    setState(() { _listening = false; _processingVoice = true; _message = null; });
                    try {
                      final transcript = (await _voiceInput.stop()).trim();
                      if (!mounted) return;
                      setState(() {
                        if (transcript.isEmpty) {
                          _controller.text = _textBeforeVoice;
                          _message = 'No se detecto voz. Puedes intentarlo nuevamente.';
                        } else {
                          final separator = _textBeforeVoice.trim().isEmpty ? '' : ' ';
                          _controller.text = '${_textBeforeVoice.trimRight()}$separator$transcript';
                          _controller.selection = TextSelection.collapsed(offset: _controller.text.length);
                          _message = 'Transcripcion lista. Revisala antes de enviar.';
                        }
                      });
                    } on VoiceInputException catch (error) {
                      if (mounted) setState(() { _controller.text = _textBeforeVoice; _message = error.message; });
                    } finally {
                      if (mounted) setState(() => _processingVoice = false);
                    }
                  }

                  Future<void> _cancelVoice() async {
                    if (!_listening || _processingVoice) return;
                    _voiceLimitTimer?.cancel();
                    _voiceLimitTimer = null;
                    try {
                      await _voiceInput.cancel();
                    } on VoiceInputException catch (error) {
                      if (mounted) setState(() => _message = error.message);
                    } finally {
                      if (mounted) setState(() {
                        _listening = false;
                        _controller.text = _textBeforeVoice;
                        _controller.selection = TextSelection.collapsed(offset: _controller.text.length);
                        _message ??= 'Reconocimiento cancelado.';
                      });
                    }
                  }

                  Future<void> _send() async {
                    final text = _controller.text.trim();
                    if (text.isEmpty || _loading) return;
                    setState(() { _loading = true; _message = null; });
                    try {
                      final result = await _commandService.execute(text);
                      if (!mounted) return;
                      final status = result['status'];
                      final message = result['message'];
                      setState(() {
                        _message = message is String ? message : 'No pude interpretar el comando.';
                        if (status == 'EXECUTED') _controller.clear();
                      });
                    } on RuntimeAiCommandException catch (error) {
                      if (mounted) setState(() => _message = error.message);
                    } finally {
                      if (mounted) setState(() => _loading = false);
                    }
                  }

                  @override
                  Widget build(BuildContext context) => Card(
                    child: Padding(
                      padding: const EdgeInsets.all(16),
                      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                        const Text('¿Qué quieres hacer?', style: TextStyle(fontSize: 18, fontWeight: FontWeight.bold)),
                        const SizedBox(height: 12),
                        TextField(
                          controller: _controller,
                          maxLength: 2000,
                          decoration: const InputDecoration(hintText: 'Escribe una instruccion...', border: OutlineInputBorder()),
                          onSubmitted: (_) => _send(),
                        ),
                        if (_listening) const Padding(
                          padding: EdgeInsets.only(bottom: 8),
                          child: Row(children: [
                            SizedBox(width: 16, height: 16, child: CircularProgressIndicator(strokeWidth: 2)),
                            SizedBox(width: 8),
                            Text('Escuchando...'),
                          ]),
                        ),
                        Wrap(spacing: 8, runSpacing: 8, children: [
                          if (!_listening)
                            OutlinedButton.icon(
                              key: const Key('voice-start'),
                              onPressed: (_loading || _processingVoice) ? null : _startVoice,
                              icon: const Icon(Icons.mic_none),
                              label: Text(_processingVoice ? 'Transcribiendo...' : 'Hablar'),
                            )
                          else ...[
                            FilledButton.tonalIcon(
                              key: const Key('voice-stop'),
                              onPressed: _stopVoice,
                              icon: const Icon(Icons.stop),
                              label: const Text('Detener'),
                            ),
                            TextButton(
                              key: const Key('voice-cancel'),
                              onPressed: _cancelVoice,
                              child: const Text('Cancelar'),
                            ),
                          ],
                          FilledButton(
                            key: const Key('command-send'),
                            onPressed: (_loading || _listening || _processingVoice) ? null : _send,
                            child: Text(_loading ? 'Generando...' : 'Enviar'),
                          ),
                        ]),
                        if (_message != null) Padding(
                          padding: const EdgeInsets.only(top: 12),
                          child: Text(_message!),
                        ),
                      ]),
                    ),
                  );
                }
                """;
    }

    private static String tile(ApplicationEntity entity) {
        return "        Card(child: ListTile(\n"
                + "          title: const Text('" + entity.name().replace("'", "\\'") + "'),\n"
                + "          trailing: const Icon(Icons.chevron_right),\n"
                + "          onTap: () => Navigator.of(context).push(MaterialPageRoute<void>(builder: (_) => const "
                + entity.technicalName() + "ListScreen())),\n        )),";
    }

    public static String readme(ApplicationSchema schema) {
        return """
                # %s frontend

                Aplicacion Flutter generada por LogicDraft para Android y Windows. Consume el backend Spring real;
                no contiene mocks, autenticacion ni almacenamiento offline.

                ## Requisitos y ejecucion

                1. Instala una version estable de Flutter con soporte Android y Windows.
                2. Ejecuta `flutter doctor` y selecciona un dispositivo con `flutter devices`.
                3. Materializa los runners compatibles con tu SDK: `flutter create --platforms=android,windows .`.
                4. Ejecuta `flutter pub get`.
                5. Ejecuta `flutter analyze`.
                6. Ejecuta `flutter test`.
                7. Ejecuta `flutter run`.

                ## API_BASE_URL

                La URL por defecto es `http://localhost:8080`. En Windows funciona cuando Spring se ejecuta en la
                misma PC:

                `flutter run -d windows --dart-define=API_BASE_URL=http://localhost:8080`

                En un telefono Android fisico, `localhost` es el propio telefono, no la PC. Usa una de estas opciones:

                - IP local de la PC, sin hardcodearla: `flutter run --dart-define=API_BASE_URL=http://<IP-DE-LA-PC>:8080`.
                - USB con `adb reverse tcp:8080 tcp:8080` y luego la URL `http://localhost:8080`.

                Si Spring usa otro `SERVER_PORT`, `API_BASE_URL` debe apuntar al mismo puerto. Por ejemplo:

                Backend en PowerShell:

                ```powershell
                $env:SERVER_PORT="8082"
                mvn spring-boot:run
                ```

                Frontend Windows:

                ```powershell
                flutter run -d windows --dart-define=API_BASE_URL=http://localhost:8082
                ```

                `SERVER_PORT` y el puerto de `API_BASE_URL` tienen que coincidir.

                ## Comandos de IA (solo CREATE)

                En Home puedes escribir una instruccion como `Registra una categoria llamada Cabello`.
                Flutter envia el texto a `POST /api/ai/commands` del backend generado. El backend valida la
                operacion y guarda mediante sus servicios CRUD. Las listas consultan PostgreSQL al abrirse o
                refrescarse. Inicia tambien el ai-service de LogicDraft y Ollama; configura `AI_SERVICE_URL` en
                el backend generado si el servicio no esta en `http://localhost:8000`. El ZIP no incluye el
                ai-service; sin el, el CRUD manual sigue disponible. La voz solo rellena este mismo campo: nunca
                llama al backend ni ejecuta el comando automaticamente. Los comandos UPDATE/DELETE no estan incluidos.

                Pruebas recomendadas:

                - Completo: `Registra un personal llamado Jose, edad 23, telefono 78159999`.
                - Incompleto: `Registra un personal llamado Jose, edad 23`.
                - Con relacion: `Registra un corte Degradado de 25 en la categoria Cabello`.
                - Con N:M: `Registra un alumno Gabriel y relacionalo con Matematicas y Fisica`.

                Una respuesta `NEEDS_CLARIFICATION` muestra el mensaje real y conserva el comando en el campo. Editalo
                para agregar los datos indicados y envia nuevamente la instruccion completa. El servidor no mantiene
                una conversacion ni combina mensajes sueltos. Mientras una solicitud esta en curso, **Enviar** queda
                deshabilitado y pulsarlo otra vez no crea una segunda solicitud simultanea. Solo `EXECUTED` limpia el
                texto. Los errores de tipo, entidad, atributo o relacion no guardan registros parciales.

                ## Relaciones muchos a muchos

                Los formularios de la entidad propietaria cargan los registros relacionados desde sus endpoints y
                muestran una seleccion multiple real. Para crear la asociacion, registra primero los elementos del
                otro extremo, marca uno o varios y guarda. Al editar se cargan los IDs existentes; desmarcar un
                elemento elimina solo la asociacion. El backend rechaza IDs inexistentes y evita parejas duplicadas.
                Una relacion con atributos propios se genera como entidad asociativa explicita: su formulario carga
                por separado ambos extremos, exige seleccionarlos cuando el esquema los declara obligatorios y envia
                sus IDs junto con los atributos propios. Registra primero los dos extremos. Si no existen opciones o
                falla su carga, el formulario lo indica sin inventar IDs. Un conflicto HTTP 409 conserva el formulario
                y sus valores para que revises si esa pareja ya existe.

                Para telefonos modelados como STRING, el backend puede unir grupos inequivocos como
                `781, 543, 23` sin alterar digitos ni ceros iniciales. Revisa siempre la transcripcion antes de
                enviarla. Una secuencia ambigua o un telefono modelado como numero solicita correccion; las comas de
                importes y otros atributos no se modifican.

                ## Voz local (Whisper Tiny + sherpa-onnx)

                La captura usa `record` y la transcripcion usa `sherpa_onnx` dentro del dispositivo. No se envia audio
                a Internet ni a una API comercial. Solo el texto que revises y envies manualmente usa
                `POST /api/ai/commands`. Pulsa **Hablar**, dicta, pulsa **Detener**, corrige el texto si hace falta y
                finalmente pulsa **Enviar**. **Cancelar** conserva exactamente el texto que ya estaba escrito. Para
                probar voz sin crear un registro, detente y revisa el texto, pero no pulses **Enviar**.

                Una vez instalados los paquetes y copiados los modelos, la captura, VAD y transcripcion funcionan
                localmente sin Internet. El registro aun requiere que Flutter alcance Spring y que Spring alcance
                PostgreSQL; la interpretacion textual requiere el ai-service y Ollama locales.

                El modelo no se incluye en el ZIP por su tamano. Descarga el modelo oficial multilingue (no el
                `tiny.en`, que solo reconoce ingles):

                `https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-tiny.tar.bz2`

                Extrae y copia en `assets/voice/` estos tres archivos, manteniendo exactamente sus nombres:

                - `tiny-encoder.int8.onnx` (aprox. 12 MB)
                - `tiny-decoder.int8.onnx` (aprox. 86 MB)
                - `tiny-tokens.txt` (aprox. 0.8 MB)
                - `silero_vad.onnx` (detector de actividad de voz)

                Descarga Silero VAD desde la distribucion oficial:

                `https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx`

                El modelo cuantizado agrega aproximadamente 99 MB antes del empaquetado. La primera carga lo copia al
                directorio de soporte privado de la aplicacion. `sherpa-onnx` esta bajo Apache-2.0 y Whisper bajo MIT;
                conserva los avisos de licencia al redistribuir sus componentes o el modelo.

                ### Windows

                Habilita el acceso al microfono para aplicaciones de escritorio en **Configuracion > Privacidad y
                seguridad > Microfono**. Windows x64 es la plataforma de desarrollo cubierta por esta fase. Si no se
                detecta entrada, revisa tambien que exista un dispositivo de grabacion habilitado.

                ### Android (preparado, validacion fisica pendiente)

                Despues de `flutter create`, agrega dentro de `<manifest>` en
                `android/app/src/main/AndroidManifest.xml`:

                ```xml
                <uses-permission android:name="android.permission.RECORD_AUDIO" />
                ```

                `record` solicita el permiso en tiempo de ejecucion. La misma inferencia local y los mismos assets se
                usan en Android, pero esta fase no afirma una prueba fisica Android hasta ejecutarla en un dispositivo
                compatible; CPU, RAM y tamano de APK pueden hacer Whisper Tiny poco conveniente en equipos modestos.

                Los errores de permiso, microfono ausente, modelo faltante, audio vacio y fallo de transcripcion se
                muestran en la tarjeta. Ninguno provoca un envio automatico. Si el ai-service o Spring no estan
                disponibles, el error aparece solamente al pulsar **Enviar**, mediante el flujo textual existente.

                ### Calidad, numeros y limites

                La captura es PCM signed de 16 bits little-endian, mono, a 16 kHz; se convierte a `Float32` normalizado
                antes de Whisper. Al pulsar **Detener**, la app espera el cierre real del stream para no perder el ultimo
                bloque. Silero VAD recorta solamente el silencio exterior, conservando 200 ms de margen y las pausas
                internas entre palabras o digitos. La captura se detiene automaticamente a los 30 segundos.

                Whisper se configura en espanol, tarea `transcribe` y `tailPaddings: 300`, valor recomendado por
                sherpa-onnx para modelos multilingues. La API usada no expone una penalizacion de repeticion especifica
                para Whisper; `blankPenalty` corresponde a decodificadores CTC y no se aplica. LogicDraft no elimina,
                completa ni normaliza secuencias numericas: telefonos con digitos repetidos deben conservarse. Whisper
                Tiny puede aun equivocarse con listas largas de digitos; dicta con pausas breves, revisa el campo y
                corrigelo antes de **Enviar**. Para mayor precision puede usarse un modelo multilingue mayor, con mayor
                consumo de memoria, almacenamiento y tiempo de inferencia.

                Si aparece "backend no disponible", verifica que Spring este iniciado, el puerto, la IP local y el
                acceso de red. CORS se configura en Spring mediante `CORS_ALLOWED_ORIGINS`; Android y Windows nativos
                no se comportan como un navegador respecto a CORS.

                Los directorios `android/` y `windows/` identifican las plataformas objetivo. El comando `flutter create`
                materializa sus runners usando las plantillas compatibles con el SDK instalado y tambien permite
                refrescarlos cuando Flutter se actualiza. LogicDraft ya incluye `lib/main.dart` y
                `test/widget_test.dart`; Flutter conserva esos archivos existentes y completa el scaffolding faltante.
                """.formatted(schema.projectName());
    }

    public static String platformNotice(String platform) {
        return "Plataforma objetivo " + platform + ". El runner nativo puede regenerarse de forma segura con "
                + "`flutter create --platforms=android,windows .` desde frontend/.\n";
    }
}
