# Contrato del planificador y ejecutor UML

El contrato de operaciones existente y sus ejecutores se conservan. El agente propone; solo la aceptación explícita ejecuta el lote transaccional en Spring Boot.

## Contexto autorizado

`AgentProjectContextService` obtiene el documento del proyecto tras comprobar acceso. El campo adicional `context.uml` transporta `version`, nodos y relaciones tipados, IDs reales, kinds, atributos, métodos, parámetros, literales, cardinalidades y metadatos de clases asociativas. Excluye posiciones y metadatos arbitrarios. Las listas anteriores siguen disponibles para compatibilidad. Si falta `uml`, FastAPI admite el contexto anterior; no reconstruye permisos o clasificadores a partir de descripciones textuales.

`version` sigue siendo la versión documental (1 o 2), no la revisión de concurrencia. La revisión base de una propuesta sigue bajo control del servicio de conversaciones.

## Resultado de planificación

La respuesta conserva `answer` y `operations`, y añade:

- `intent`: `INFORMATIONAL`, `MUTATION` o `NEEDS_CLARIFICATION`.
- `outcome`: `INFORMATIONAL`, `PROPOSAL`, `ALREADY_SATISFIED` o `NEEDS_CLARIFICATION`.
- `repairAttempts`: 0 o 1. Spring acepta respuestas anteriores sin estos campos, pero una mutación vacía antigua se presenta como aclaración, nunca como ejecución.

La intención se obtiene del mensaje humano, no de la salida del modelo. Las preguntas e hipótesis no autorizan mutaciones. Los comandos reconocidos por el planificador acotado resuelven nombres y cardinalidades contra el contexto, sin inventar clases o IDs. Su prompt y esquema limitan al proveedor a las familias de operaciones requeridas. La salida aún pasa los validadores existentes.

Una relación Producto → Categoria con muchos productos por categoría y exactamente una categoría por producto usa `sourceCardinality=ZERO_MANY`, `targetCardinality=ONE_ONE`. «Al menos uno» exige `ONE_MANY`; muchos/varios/hartos/artos no imponen mínimo uno. Una relación igual es un no-op; cardinalidades distintas requieren una solicitud explícita de actualización.

El lote de eliminación de Producto.«Precio de venta» y creación de compra.CANTIDAD Integer debe conservar ambas operaciones. Un lote parcial se repara una sola vez o se rechaza completo. Una eliminación no autorizada se bloquea sin reparación. El resto de CRUD reutiliza el proveedor y los controles de completitud, cobertura y semántica existentes; la gramática acotada no constituye un intérprete universal de español.

Los rechazos semánticos de FastAPI usan HTTP 422 con `detail.code=AGENT_SEMANTIC_REJECTION` y `detail.message`. Spring transmite únicamente este error de dominio acotado; no expone cuerpos arbitrarios ni trazas. El chat conserva el motivo, la lista de operaciones y aceptar/rechazar. Las relaciones muestran sus cardinalidades por extremo.

## Aplicación y verificaciones

La interpretación de creaciones compuestas sigue a cargo de Ollama con la representación compacta `classes/relationships`. `structure_grounding.py` comprueba la evidencia del mensaje por propietario: la identidad de un atributo solicitado es `(clase, nombre, tipo, PK)`. No se comparte el PK de un `ID` con otra clase. Si no se pueden asociar los atributos a un propietario, se pide aclaración; no se reparte por similitud. Una solicitud que solo indique cantidades, sin nombres y tipos de atributos, no autoriza inventarlos. Se conserva un máximo de una reparación, con motivo específico, y métricas de tiempo/tokens de Ollama sin registrar contenido del prompt.

Las menciones iniciales con Markdown se normalizan también en Spring y React. Un desacuerdo de intención nunca conserva una afirmación de propuesta preparada sin operaciones. La presentación del mensaje se deriva del estado persistido (`PENDING`, `INVALID`, `STALE`), no del texto de Ollama. React conserva actualizaciones recibidas antes del mensaje e impide que un HTTP atrasado restaure `PENDING` sobre un estado final. La tarjeta se renderiza exclusivamente desde `proposal` y muestra atributos por clase. El fixture compartido `backend/src/test/resources/ai/compound-uml.json` verifica siete operaciones desde HTTP hasta aceptación y recuperación con persistencia simulada, además de renderizado real de la tarjeta con React DOM Server.

Se conservan permisos OWNER/EDITOR, VIEWER de solo lectura, revisión base, locks, validación documental, persistencia atómica y eventos posteriores a commit. Ninguna respuesta del proveedor constituye confirmación del usuario. No hay migración de datos ni cambios en imágenes o generadores.

Regresiones principales: `tests/agent/test_execution_plan.py`, `ContextualAgentServiceTest`, `AiServiceClientTest`, `UmlV2AgentContextAndProposalRegressionTest` y `Frontend/tests/agentAuthorization.test.mjs`. Las pruebas Java de integración usan repositorios simulados y HTTP local simulado; no PostgreSQL real.

La prueba opt-in `tests/agent/test_local_executor_integration.py` usa `llama3.2:latest` ya instalado en `127.0.0.1:11434`, sin leer `.env` ni descargar modelos. Se habilita con `LOGICDRAFT_TEST_LOCAL_EXECUTOR=1`. Comprueba los dos mensajes exactos con el planificador y proveedor real; no demuestra reconocimiento libre sin planificación, otros modelos, interacción de navegador ni un despliegue actualizado.
