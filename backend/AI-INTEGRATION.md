# Integracion de IA

## Barra inferior del editor

`POST /api/proyectos/{projectId}/ai/diagram/assist` requiere JWT y acceso de
OWNER/EDITOR al proyecto. El módulo `ai/editor` delega en `ContextualAgentService`
(FastAPI `/api/agent/ask`), sin crear conversaciones ni guardar diagramas.
No modifica los endpoints ni servicios de conversaciones compartidas.

Usa `AgentAskRequest`: `message` no vacío de hasta 4000 caracteres,
`selectedNodeId`/`selectedEdgeId` opcionales de hasta 100, `recentEvents` obligatorio
(máximo 25) y `conversation` opcional (máximo 10; la barra envía una lista vacía).
Respuesta `AgentAskResponse`: `answer`, `operations`, `intent`, `outcome`,
`repairAttempts`. Las aclaraciones y solicitudes ya satisfechas son respuestas
válidas sin operaciones. Las entradas inválidas producen 400; no autoriza mutaciones
una consulta o una sugerencia ambigua.

El adaptador verifica permisos antes y después de inferir; vuelve a validar el lote
contra el diagrama persistido más reciente con los validadores semántico y contextual
existentes. Respeta los contratos actuales y su compatibilidad v1/v2. La propuesta
sigue sin persistirse hasta la aceptación del usuario y el guardado normal con
`baseRevision` (409 ante una revisión obsoleta). La ruta `/interpret` continúa disponible
por compatibilidad y las imágenes conservan su flujo independiente.

Validación de esta reparación: pruebas `EditorBarAiControllerTest`,
`EditorBarAiSecurityTest`, `EditorBarAiServiceTest` y suite de regresión del backend.
En este entorno `./mvnw.cmd test` falla al iniciar el Wrapper (`Cannot start maven
from wrapper`, error de PowerShell `icm` al indexar una matriz nula); se usa
`mvn.cmd test` con Maven instalado. Las integraciones opcionales con PostgreSQL
y Flutter requieren su configuración explícita. La inferencia con Ollama real
se comprueba manualmente con los pasos de `Frontend/DIAGRAM-AI.md`.

## Interpretacion de diagramas

`POST /api/ai/diagram/interpret` usa el mismo JWT y request `{"prompt":"..."}`.
No recibe IDs ni aplica operaciones. Mantiene disponible el endpoint generico.
Respuesta tipada:

```json
{"operations":[
  {"type":"ADD_ENTITY","entity":{"name":"Cliente","attributes":[{"name":"id","dataType":"Long","primaryKey":true,"nullable":false}]}},
  {"type":"ADD_ATTRIBUTE","entityName":"Cliente","attribute":{"name":"telefono","dataType":"String","primaryKey":false,"nullable":true}},
  {"type":"ADD_RELATIONSHIP","relationship":{"sourceEntity":"Cliente","targetEntity":"Pedido","sourceCardinality":"ONE_ONE","targetCardinality":"ZERO_MANY"}}
]}
```

Las tres operaciones tienen campos excluyentes. Spring valida claves exactas,
campos requeridos, nombres no blancos de hasta 100 caracteres, booleanos reales,
tipos y relaciones enumerados, hasta 50 operaciones y 100 atributos por entidad.
Una lista vacia significa que no se pudo identificar una accion; no significa fallo.
La ruta generica valida forma pero no existencia porque no tiene proyecto. La ruta
contextual del editor y el agente comprueban las entidades contra el diagrama persistido.

Tipos: String, Long, Integer, Double, Boolean, Date, DateTime.
Cardinalidades por extremo: ZERO_ONE, ONE_ONE, ZERO_MANY y ONE_MANY. Una N:M usa
un valor many en ambos extremos. `name` y `joinTableName` son opcionales.
FastAPI normaliza sinonimos; Spring solo acepta los tipos canonicos resultantes.
Estos tipos pertenecen al contrato de interpretacion; no cambian los tipos SQL
existentes del editor. La conversion al editor corresponde a una etapa posterior.

Tras obtener `$headers` con el login del ejemplo inferior:

```powershell
$body = @{ prompt = 'Crea una entidad Cliente con id, nombre y correo' } | ConvertTo-Json
Invoke-RestMethod -Method Post -Uri "$baseUrl/api/ai/diagram/interpret" -Headers $headers -ContentType 'application/json' -Body $body -TimeoutSec 100
```

`POST /api/ai/generate` requiere el JWT existente y recibe `{"prompt":"..."}`.
Devuelve `{"content":"..."}`. El prompt es obligatorio, no puede estar en blanco
y admite hasta 10000 caracteres. No interpreta la respuesta ni ejecuta operaciones.

Configuracion en `.env` o variables del sistema (estas tienen prioridad):

```dotenv
AI_SERVICE_URL=http://localhost:8000
AI_SERVICE_TIMEOUT_SECONDS=90
```

El cliente usa RestClient. El timeout de lectura es 90 segundos por defecto;
el de conexion es el menor entre 10 segundos y el timeout configurado.
El timeout debe ser positivo. No requiere dependencias nuevas.

Los errores usan el formato del GlobalExceptionHandler: servicio inaccesible o
upstream 503 -> 503; timeout local o upstream 504 -> 504; otros errores 4xx/5xx
del upstream o respuesta invalida -> 502. Los detalles del upstream no se exponen.
La validacion de solicitudes usa el 400 existente. Sin JWT se devuelve 401.

Desde `backend`, arrancar con `mvnw.cmd spring-boot:run` (o Maven instalado).
Luego ejecutar en PowerShell, con una cuenta existente:

```powershell
$baseUrl = 'http://localhost:8081'
$credentials = Get-Credential -Message 'Cuenta existente de LogicDraft: email y password'
$loginBody = @{ email = $credentials.UserName; password = $credentials.GetNetworkCredential().Password } | ConvertTo-Json
$login = Invoke-RestMethod -Method Post -Uri "$baseUrl/api/auth/login" -ContentType 'application/json' -Body $loginBody
$headers = @{ Authorization = "Bearer $($login.token)" }
$body = @{ prompt = 'Responde solamente JSON. Define una entidad Cliente con atributos id, nombre y correo.' } | ConvertTo-Json
Invoke-RestMethod -Method Post -Uri "$baseUrl/api/ai/generate" -Headers $headers -ContentType 'application/json' -Body $body -TimeoutSec 100
```

Para comprobar la validacion, enviar `{"prompt":""}` con el mismo JWT.
Para comprobar seguridad, omitir Authorization. Para probar indisponibilidad sin
detener FastAPI, iniciar una instancia de prueba con AI_SERVICE_URL apuntando a
un puerto local cerrado. Las pruebas AiServiceClientTest reproducen esa condicion
con un servidor HTTP efimero y no requieren PostgreSQL, JWT ni Ollama.
