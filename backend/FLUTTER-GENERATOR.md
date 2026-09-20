# Flutter + Fullstack Generator V1

## Flujo

```text
ApplicationSchema
├── SpringBootGenerator → backend/
└── FlutterGenerator    → frontend/
                         ↓
                  FullStackGenerator
                         ↓
                  <technical-name>.zip
```

Los dos generadores reciben el mismo `ApplicationSchema`. Flutter reutiliza `SpringNames.restRoute`
y `RelationshipPlanner`; por eso rutas, lado propietario y nombres de IDs de relaciones coinciden
con los DTO generados por Spring. Flutter nunca interpreta texto visual ni consume el ZIP backend.

## Endpoints

- `POST /api/proyectos/{projectId}/generator/backend` conserva la exportacion Spring existente.
- `POST /api/proyectos/{projectId}/generator/fullstack` descarga backend + frontend.

Ambos requieren JWT y rol `OWNER` o `EDITOR`. `VIEWER` y usuarios externos reciben 403.

## Flutter generado

El frontend usa Material, `package:http` 1.6.0 y Dart estricto. Cada entidad genera modelo,
servicio REST, lista y formulario. No hay mocks. Los formularios omiten PK generadas y usan controles
segun `CanonicalType`. Una relacion simple usa dropdown; N:M carga el endpoint relacionado y muestra
un `FormField<Set<T>>` con `CheckboxListTile`. Al crear envia IDs unicos y al editar inicializa el
selector con los IDs de la respuesta. Desmarcar reemplaza la asociacion real en Spring.

`ApiConfig` recibe `API_BASE_URL` con `--dart-define` y usa `http://localhost:8080` por defecto.
El README generado explica Windows, Android fisico, IP local y `adb reverse`.

Los fuentes Dart son compatibles con Android y Windows. Las carpetas de plataforma incluyen una
referencia de bootstrap; para materializar o refrescar runners nativos de acuerdo con el Flutter
instalado se utiliza `flutter create --platforms=android,windows .` desde `frontend/`. Este comando es
una accion local del usuario y nunca se ejecuta en el servidor ni durante una exportacion.

## Comandos CREATE y voz

La pantalla principal conserva el borrador cuando Spring responde `NEEDS_CLARIFICATION`, muestra el
mensaje devuelto y permite editar y reenviar el comando completo. Solo `EXECUTED` limpia el texto. Un
bloqueo `_loading` y el boton deshabilitado evitan dos solicitudes simultaneas por doble pulsacion.
No hay memoria conversacional ni envio automatico: el microfono produce una transcripcion editable y
el usuario decide cuando pulsar Enviar. READ, UPDATE y DELETE mediante IA no estan soportados.

Para probar: usa un CREATE completo, repitelo omitiendo un campo obligatorio, prueba una relacion por
nombre y realiza el mismo flujo mediante voz. La captura, Silero VAD y Whisper funcionan sin Internet
una vez instaladas dependencias y modelos; el guardado requiere Spring/PostgreSQL locales y la
interpretacion requiere ai-service/Ollama locales.

Para N:M, crea primero los registros relacionados, selecciona varios desde el formulario y verifica
que siguen seleccionados al editar. El CREATE por IA acepta una lista de nombres existentes; una
coincidencia ausente o ambigua pide revision sin persistencia parcial. Las relaciones con atributos
propios se modelan como entidad asociativa explicita.

## ZIP fullstack

Todas las entradas quedan bajo una sola raiz validada:

```text
<technical-name>/
├── README.md
├── backend/
└── frontend/
```

`SafeZipWriter` rechaza rutas absolutas, `..`, backslashes, drive letters y normalizaciones
inesperadas. Ordena entradas y fija timestamps para producir ZIPs reproducibles.

## Fuera de alcance

- autenticacion dentro de la aplicacion generada;
- SQLite, offline y sincronizacion;
- IA, voz y generacion de APK;
- BLoC, Riverpod, Redux, GetX o arquitectura compleja;
- ejecucion de Flutter, Maven, Docker o SQL desde endpoints.
