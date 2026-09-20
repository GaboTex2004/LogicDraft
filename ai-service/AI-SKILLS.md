# AI Skills / Robustness V1

LogicDraft no entrena ni ajusta modelos en esta fase. Los tests verifican código y
contratos; no son datos de entrenamiento ni modifican `llama3.2`.

## Arquitectura

- `app/services/providers`: frontera del proveedor LLM. `OllamaProvider` es el
  adaptador local actual y `AIService` evita acoplar las capacidades a HTTPX.
- `app/skills/diagram`: validaciones deterministas de completitud y construcción
  del único intento de reparación. `DiagramAIService` conserva la orquestación y
  el contrato público existente.
- `app/skills/agent`: resolución factual determinista y construcción del prompt
  consultivo para preguntas que sí necesitan razonamiento del LLM.
- `app/schemas`: contratos Pydantic estrictos compartidos por API y capacidades.
- `app/api`: traducción HTTP; no contiene reglas de dominio.

La estructura conserva los servicios y rutas existentes para evitar una
reescritura o ruptura de imports, y extrae únicamente las responsabilidades que
necesitan evolucionar de forma independiente.

## Diagram Skill

La generación estructurada mantiene `POST /api/ai/diagram/interpret` y separa:

1. extracción de un documento JSON;
2. validación estricta del schema y tipos compatibles;
3. normalización y deduplicación de operaciones;
4. validación de cumplimiento de cantidades, nombres y relaciones explícitas;
5. como máximo un intento de reparación.

El repair recibe la solicitud original, un resumen seguro del error, el contexto
semántico y, cuando existe, el lote anterior incompleto. Siempre pide el lote
completo, no únicamente las operaciones faltantes. Si el segundo lote falla, el
endpoint devuelve el error controlado existente y no inicia otro ciclo.

## Agent Skill

Las preguntas factuales soportadas se contestan directamente desde
`AgentContext`, sin invocar Ollama: cantidades y nombres de entidades, atributos,
primary keys, relaciones/cardinalidades, cantidades de relaciones y entidad
seleccionada. Una consulta conceptual utiliza Ollama con un prompt que separa
hechos observados de recomendaciones. Una peticion de cambio puede devolver un
contrato `answer + operations` limitado a `ADD_ENTITY`, `ADD_ATTRIBUTE` y
`ADD_RELATIONSHIP`. N:M y entidad asociativa reutilizan exactamente las operaciones
del Diagram Skill; no existe una via de escritura privilegiada.

Spring construye el contexto usando el diagrama persistido y autorizado. Solo
proyecta entidades, atributos, relaciones, cardinalidades e IDs necesarios para
validar la selección; omite posiciones y estilos. En el frontend el agente queda
bloqueado mientras hay cambios pendientes, un guardado en curso o un error de
guardado. Por tanto, V1 consulta el último estado persistido confirmado, no una
copia arbitraria del canvas. Spring y React validan las operaciones antes de
aplicarlas; Ollama no escribe el documento ni PostgreSQL directamente.

## Tests

- `tests/diagram`: completitud, repair exitoso/fallido y límite de un retry.
- `tests/agent`: consultas factuales sin proveedor y delegación conceptual.
- Los tests históricos continúan cubriendo schemas, normalización, parser,
  cardinalidades, proveedor y compatibilidad de rutas.
