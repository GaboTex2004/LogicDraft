# LogicDraft AI Service

## IA runtime de aplicaciones exportadas (CREATE V1)

La IA solo admite CREATE. READ, UPDATE y DELETE por IA permanecen fuera de alcance. La voz del Flutter
generado se transcribe localmente y reutiliza este mismo flujo textual; el ai-service nunca recibe audio.

Cuando reconoce un CREATE incompleto, conserva la entidad y los valores parciales, calcula los campos
y relaciones obligatorios ausentes y responde `NEEDS_CLARIFICATION` con `missingFields`. Las PK
generadas se excluyen. No inventa valores ni persiste: Spring repite la validacion obligatoria y es el
unico componente que llama a los servicios CRUD. El cliente conserva el comando para que el usuario lo
complete y reenvie entero; no existe memoria conversacional ilimitada.

Ejemplos de prueba:

- Completo: `Registra un personal llamado Jose, edad 23, telefono 78159999`.
- Incompleto: `Registra un personal llamado Jose, edad 23`.
- Relacionado: `Registra un corte Degradado de 25 en la categoria Cabello`.

Ollama y su modelo pueden funcionar localmente sin Internet despues de instalarlos. La transcripcion
Flutter tambien es local cuando sus modelos de voz ya estan instalados.

Los campos identificados explicitamente como telefono se procesan solo cuando el esquema los declara
`STRING`. Los grupos exclusivamente numericos separados por comas se concatenan sin reordenar ni
inventar digitos y conservan los ceros iniciales. La secuencia debe aparecer una unica vez, asociada a
un telefono en el texto original. Ante ambiguedad, ausencia de evidencia o un telefono modelado como
numero se devuelve `NEEDS_CLARIFICATION`. Ningun importe, decimal u otro atributo usa esta regla.

El backend Spring generado puede llamar a `POST /api/runtime/interpret` con el texto del usuario y un esquema JSON derivado de `ApplicationSchema`. Esta ruta está separada de las rutas de IA del editor y del agente. Devuelve una interpretación estructurada (`INTERPRETED`, `NEEDS_CLARIFICATION` o `NOT_UNDERSTOOD`); no escribe en PostgreSQL ni ejecuta código. El backend generado valida esa respuesta y llama a su servicio CRUD para crear el registro.

Para relaciones `multiple=true`, la interpretacion coloca en `relations` una lista
de nombres mencionados por el usuario. El ai-service no inventa IDs; Spring resuelve
cada nombre contra registros existentes, rechaza coincidencias ausentes o ambiguas
y conserva la transaccion atomica. Solo CREATE esta habilitado.

Para desarrollo local, inicia Ollama con el modelo configurado en `OLLAMA_MODEL`, ejecuta este servicio con `python -m app.main` desde `ai-service` y configura `AI_SERVICE_URL` en el backend generado si no usas `http://localhost:8000`. El ZIP fullstack no incluye ni despliega este servicio; sin él, los formularios CRUD siguen funcionando. Los comandos UPDATE/DELETE y la voz no están implementados.

La ruta real es `POST http://localhost:8000/api/runtime/interpret`. Recibe exactamente:

```json
{
  "text": "Registra una categoria llamada Cabello",
  "schema": "{\"entities\":[...]}"
}
```

`schema` es un JSON serializado como string y proviene del `ApplicationSchema` del backend generado. Una respuesta CREATE válida contiene `status`, `operation`, `entity`, `values`, `relations` y `message`. Para probarla sin Flutter puede usarse `/docs` o enviar ese cuerpo directamente; no debe enviarse únicamente `text` porque el ai-service no lee clases Java ni conoce el esquema exportado por otro medio.

Microservicio FastAPI que aísla la integración de LogicDraft con proveedores de IA. Actualmente ofrece health check y generación genérica de texto mediante un Ollama instalado localmente. No modifica diagramas ni descarga modelos.

## Implementado

- Configuración desde `ai-service/.env` mediante `pydantic-settings`.
- Abstracción `BaseAIProvider` e implementación `OllamaProvider` con `httpx`.
- Servicio `AIService` independiente de HTTP.
- Health check tolerante a una caída de Ollama.
- Generación genérica de texto sin intervenir en diagramas.
- Errores controlados de configuración, conexión, timeout, modelo inexistente y respuesta inválida.

## Estructura

```text
app/
├── api/routes/          # Rutas y traducción de errores a HTTP
├── core/config.py       # Configuración de entorno
├── schemas/ai.py        # Contratos Pydantic
├── services/ai_service.py
└── services/providers/  # Contrato base y proveedor Ollama
```

La ubicación existente `services/providers` se conserva porque ya separa correctamente la orquestación de la integración externa.

## Requisitos e instalación

- Python 3.10 o posterior.
- Ollama solo es necesario para generar texto; el health funciona sin él.
- Un modelo descargado manualmente para usar `/api/ai/generate`.

Desde la raíz del proyecto, en Windows PowerShell:

```powershell
cd ai-service
python -m venv .venv
.\.venv\Scripts\Activate.ps1
python -m pip install -r requirements.txt
Copy-Item .env.example .env
```

`.env` es local y está ignorado por Git. `.env.example` documenta las variables y sí debe versionarse.

En producción, estas variables deben proporcionarse mediante el entorno del despliegue. Deben cambiarse las URLs y puertos según la red utilizada, sin incluir secretos ni valores privados en `.env.example`.

## Configuración

```dotenv
AI_PROVIDER=ollama
OLLAMA_BASE_URL=http://localhost:11434
OLLAMA_MODEL=
AI_REQUEST_TIMEOUT=60
AI_SERVICE_HOST=0.0.0.0
AI_SERVICE_PORT=8000
```

- `AI_PROVIDER`: proveedor activo; actualmente solo admite `ollama`.
- `OLLAMA_BASE_URL`: URL de Ollama.
- `OLLAMA_MODEL`: nombre exacto del modelo instalado en esta computadora.
- `AI_REQUEST_TIMEOUT`: timeout en segundos para Ollama.
- `AI_SERVICE_HOST` y `AI_SERVICE_PORT`: interfaz y puerto configurables.

Para seleccionar un modelo, se instala manualmente y luego se configura el mismo nombre:

```powershell
ollama pull llama3.2
ollama serve
```

```dotenv
OLLAMA_MODEL=llama3.2
```

El servicio nunca descarga modelos automáticamente.

## Ejecución

Con valores explícitos:

```powershell
.\.venv\Scripts\python.exe -m uvicorn app.main:app --host 127.0.0.1 --port 8000
```

Para respetar `AI_SERVICE_HOST` y `AI_SERVICE_PORT` del `.env`:

```powershell
.\.venv\Scripts\python.exe -m app.main
```

La documentación queda en `http://localhost:8000/docs`.

El comando explícito del `.venv` evita mezclar Python/Uvicorn globales con las
dependencias del proyecto, incluso si la consola muestra `(.venv)`.
`python -m app.main` activa el reloader; para diagnosticar, usar el primer comando
sin `--reload` y mantener una sola instancia escuchando en 8000.

`get_settings()` conserva la configuración en memoria mediante `lru_cache`.
Después de modificar `.env`, detener y volver a iniciar el servicio; no asumir
que el reloader detectará cambios de este archivo. No sobrescribir un `.env`
configurado al copiar el ejemplo. Para verificar el entorno:

```powershell
.\.venv\Scripts\python.exe -m pip check
.\.venv\Scripts\python.exe -c "from app.core.config import get_settings; s=get_settings(); print(s.ai_provider, s.ollama_model, s.ollama_base_url)"
```

Si faltan dependencias, instalarlas con `.\.venv\Scripts\python.exe -m pip install -r requirements.txt`.
Si el health indica `providerAvailable: false`, comprobar primero
`http://localhost:11434/api/tags` e iniciar `ollama serve` cuando Ollama esté apagado.

## Endpoints

Las relaciones estructuradas usan `sourceCardinality` y `targetCardinality` con
`ZERO_ONE`, `ONE_ONE`, `ZERO_MANY` o `ONE_MANY`. Si el usuario no especifica
cardinalidad, el prompt interno pide `ONE_ONE/ONE_ONE`; “uno a muchos” se expresa
como `ONE_ONE/ZERO_MANY`. El contexto aún acepta `relationshipType` antiguo y lo
convierte determinísticamente, pero una respuesta nueva del modelo que use ese
campo es rechazada por el contrato estricto.

Una N:M usa many en ambos extremos. `name` y `joinTableName` son campos opcionales
del contrato. Para una asociacion con atributos, el prompt exige una entidad
asociativa explicita y dos relaciones; no transforma silenciosamente la N:M ni
inventa columnas.

Para operaciones de diagrama, `DiagramAIService` entrega el mismo JSON Schema a
Ollama mediante `format` y fija `temperature=0`. Esto limita la forma generada y
mejora la repetibilidad, pero la respuesta todavía atraviesa parsing seguro,
canonicalización, aliases, Pydantic estricto y deduplicación. `/api/ai/generate`
continúa como generación de texto normal, sin `format` ni cambio de temperatura.
El prompt textual se compactó retirando la copia completa del schema (Ollama lo
recibe separadamente) y la solicitud del usuario queda al final, después del
contexto. En la medición contextual pasó de 6862 a aproximadamente 4600 caracteres.

`POST /api/ai/diagram/interpret` recibe `{"prompt":"..."}` y devuelve
`{"operations":[...]}`. Admite ADD_ENTITY (entity), ADD_ATTRIBUTE
(entityName, attribute) y ADD_RELATIONSHIP (relationship). No aplica operaciones.
El esquema completo esta disponible en /docs.

DiagramAIService incluye el JSON Schema en la instruccion interna y exige JSON
sin SQL, Java, Markdown ni explicaciones. Esto orienta al modelo; la garantia del
contrato la proporciona la validacion posterior, no el prompt por si solo.
`parse_llm_json_response` retira un fence completo con etiqueta json opcional e
intenta decodificar todo el documento con JSONDecoder.decode. Si falla, encuentra
el primer inicio `{` o `[` y usa JSONDecoder.raw_decode: nunca salta un candidato
roto para rescatar JSON anidado. Acepta prosa breve antes/despues (hasta 500 caracteres
por lado, letras, espacios y puntuacion simple); rechaza codigo, delimitadores
sueltos, otros documentos o escalares JSON. No combina documentos ni repara sintaxis.
Tambien rechaza claves duplicadas para impedir que una segunda clave operations
descarte silenciosamente la primera. No usa regex para extraer JSON, eval ni
ast.literal_eval. La union discriminada Pydantic sigue estricta, con campos extra
prohibidos. Una respuesta invalida produce 502, nunca operaciones sin validar.
El prompt admite hasta 10000 caracteres y no puede estar en blanco.

Normalizacion determinista, sin distinguir mayusculas:

- VARCHAR, VARCHAR(n) (longitud entera positiva), CHAR, TEXT, STRING -> String.
- INT, INTEGER -> Integer; BIGINT, LONG -> Long.
- FLOAT, DOUBLE, DOUBLE PRECISION, DECIMAL, NUMERIC, REAL -> Double.
- BOOL, BOOLEAN -> Boolean; DATE -> Date.
- DATETIME, TIMESTAMP, TIMESTAMP WITHOUT TIME ZONE, TIMESTAMP WITH TIME ZONE -> DateTime.

Los nombres canonicos tambien se aceptan en minusculas. Otros tipos se rechazan;
no existe conversion por defecto a String. Se preservan nombres, primaryKey y nullable.
Ambas variantes (con y sin diagram) usan el mismo flujo: texto, extraccion segura
del documento con JSONDecoder, canonicalizacion del envelope, normalizacion recursiva de dataType, Pydantic estricto,
deduplicacion existente y respuesta canonica. El contexto reutiliza la misma funcion
de aliases. Spring mantiene su segunda validacion sin cambios.

`canonicalize_diagram_response_shape` conserva el envelope `operations`, envuelve una
operacion directa de tipo ADD_ENTITY/ADD_ATTRIBUTE/ADD_RELATIONSHIP o una lista de
estas operaciones (incluida una lista vacia). No elimina campos extra, no completa
operaciones ni modifica el input. Es idempotente. El contenido sigue sujeto a la
validacion estricta; tipos desconocidos, booleanos invalidos y extras dan 502.
No se admiten `operation` singular (no observado), aliases arbitrarios ni diagramas
completos con `entities`/`relationships` en la raiz.

En una reproduccion real del prompt de Cliente, Ollama devolvio precisamente un
diagrama completo y agrego Pedido y una relacion no solicitados. Ese resultado
debe seguir rechazandose: no es seguro transformarlo en cambios. Se reforzo la
instruccion del modelo sobre el envelope obligatorio y los ejemplos, pero no se
garantiza que cada generacion sea correcta.

Los rechazos registran la estructura original (dict/list), hasta 20 claves raiz
sanitizadas, hasta 10 errores con su tipo y ubicacion, y,
para tipos desconocidos cortos con caracteres SQL simples, el tipo. No se registra
el prompt, la respuesta completa, JWT ni stack traces publicos; valores no seguros
se redactan. Un error del proveedor o un output fuera del contrato aun puede dar 502.

Para fallos de sintaxis se registra razon, linea y columna cuando estan disponibles.
En desarrollo puede activarse SOLO el logger `app.services.llm_json_parser` en DEBUG
para obtener `structuralPreview`: examina como maximo los primeros 1500 caracteres
y limita la vista a 1500. Redacta todas las cadenas y tokens, incluso credenciales
sin comillas; conserva puntuacion y saltos de linea. No activar logs HTTP sensibles.
Ejemplo de arranque de desarrollo desde ai-service:

```powershell
.\.venv\Scripts\python.exe -c "import logging, uvicorn; logging.basicConfig(level=logging.INFO); logging.getLogger('app.services.llm_json_parser').setLevel(logging.DEBUG); uvicorn.run('app.main:app', host='127.0.0.1', port=8000)"
```

En una reproduccion de Categoria/Producto el modelo repitio operations y dejo el
segundo bloque mal cerrado (Expecting ',' delimiter). Eso debe seguir fallando.
El ejemplo interno ahora muestra dos ADD_ENTITY completos dentro de UNA lista,
con todos los atributos de cada entidad; reemplaza el ejemplo de una sola entidad.

Pruebas locales desde ai-service:

```powershell
.\.venv\Scripts\python.exe -m unittest discover -s tests -v
.\.venv\Scripts\python.exe -m compileall app
# Si pytest esta instalado en el entorno de desarrollo:
.\.venv\Scripts\python.exe -m pytest -q
```

Las pruebas HTTP simulan solo la respuesta del modelo: verifican parsing y contrato,
no garantizan que Ollama genere siempre el mismo JSON. Para probar desde el editor,
reiniciar ai-service y usar un proyecto sin Cliente, con cambios ya guardados.
No se permite ejecutar contenido generado. Para instrucciones sin accion
identificable se permite operations vacio.

`GET /api/health`:

```json
{
  "status": "ok",
  "provider": "ollama",
  "providerAvailable": false
}
```

`providerAvailable` indica si la API de Ollama responde. Ollama apagado no hace fallar el health de FastAPI.

`POST /api/ai/generate` recibe:

```json
{ "prompt": "Explica qué es una clave primaria" }
```

Y responde:

```json
{ "content": "..." }
```

## Planificado, no implementado

- Integración Spring Boot → FastAPI.
- Operaciones estructuradas sobre diagramas.
- `DiagramAIService`, agentes, contexto y herramientas.
- Voz, visión, RAG, embeddings y proveedores remotos.

Cuando se implemente la integración, Spring Boot deberá validar permisos y cualquier operación propuesta por la IA. Este servicio no escribe directamente en PostgreSQL.
