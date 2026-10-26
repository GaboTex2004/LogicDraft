# Fase 1 — Resumen de estabilización

## Estado verificado

La rama inspeccionada es `Antony`; al comenzar estaba limpia y apuntaba al commit `98ce0c9` (`feat: completar fase 1 de LogicDraft Evolution`). La inspección se limitó al commit de Fase 1, sus contratos y sus pruebas. No se leyó `.env.deploy`.

## Funcionalidades implementadas

### Intenciones del agente

React, Java y Python comparten las categorías `INFORMATIONAL`, `MODIFICATION` y `AMBIGUOUS`. La autorización se deriva del texto del usuario, no de operaciones producidas por el modelo. Preguntas, sugerencias, negaciones e instrucciones no explícitas no autorizan cambios.

Archivos centrales:

- `Frontend/src/features/agent/services/agentIntent.ts`
- `backend/src/main/java/com/sw1/backend/ai/agent/service/AgentIntentClassifier.java`
- `ai-service/app/skills/agent/intent.py`

### Validación estructural antes de persistir

`DiagramDocumentValidator` valida documentos `version: 1` sin reescribirlos: IDs y nombres de entidades, atributos, tipos canónicos, posiciones opcionales, extremos de relaciones, cardinalidades, metadatos de Enterprise Architect y metadatos de entidades asociativas. Rechaza, entre otros casos, duplicados, referencias inexistentes, autorrelaciones, tipos no soportados y asociaciones inconsistentes. Un documento inválido produce HTTP 422.

La persistencia sigue limitada a nodos `entity`, atributos de datos y relaciones existentes. El tamaño máximo declarado es 1 000 000 bytes y el documento se almacena como JSONB.

Archivos centrales:

- `backend/src/main/java/com/sw1/backend/diagrama/validation/DiagramDocumentValidator.java`
- `backend/src/main/java/com/sw1/backend/diagrama/validation/DiagramValidationException.java`
- `backend/src/main/java/com/sw1/backend/diagrama/service/DiagramaService.java`
- `backend/src/main/java/com/sw1/backend/common/exception/GlobalExceptionHandler.java`

### Concurrencia optimista

La entidad `Diagrama` usa `@Version` sobre `revision`. `DiagramaResponse` expone `revision` y `GuardarDiagramaRequest` recibe `baseRevision`. Para crear, `baseRevision` debe ser nulo; para actualizar debe coincidir con la revisión actual. Los conflictos explícitos o detectados por JPA se traducen a HTTP 409, opcionalmente con `revisionActual` y una ruta para recargar.

El frontend conserva la revisión confirmada por el servidor, la envía separada de `version`, no descarta el lienzo local ante 409 y exige resincronización explícita. Los IDs de eventos WebSocket repetidos se ignoran.

Archivos centrales:

- `Frontend/src/features/diagram/types/diagram.types.ts`
- `Frontend/src/features/diagram/api/diagramApi.ts`
- `Frontend/src/features/diagram/pages/DiagramEditorPage.tsx`
- `Frontend/src/features/collaboration/hooks/useProjectCollaboration.ts`
- `backend/src/main/java/com/sw1/backend/diagrama/model/Diagrama.java`
- `backend/src/main/java/com/sw1/backend/diagrama/service/DiagramConflictException.java`
- `backend/src/main/resources/db/migration/V2__diagram_revision.sql`

### Flyway y esquema

V2 agrega `diagramas.revision bigint NOT NULL DEFAULT 0` sin cambiar el JSON. El perfil predeterminado mantiene Flyway desactivado y Hibernate en `validate` para no adoptar automáticamente bases anteriores. El perfil `migrated` habilita Flyway y se destina a bases nuevas o bases cuya transición ya fue verificada según `DEVELOPMENT.md`.

`version` y `revision` tienen propósitos distintos: la primera identifica el formato del documento; la segunda controla escrituras concurrentes.

## Contratos relacionados que deben preservarse

- Persistencia REST: `GET`/`PUT /api/proyectos/{proyectoId}/diagrama`.
- Documento actual: `{ version: 1, nodes, edges }`.
- IA: las operaciones estructuradas y el contexto usan entidades, atributos y cardinalidades actuales.
- Colaboración: WebSocket/STOMP publica eventos semánticos y el frontend deduplica `eventId`.
- Generación: `ApplicationSchemaMapper` transforma directamente el documento v1 a `ApplicationSchema` para Spring Boot/Flutter.
- EA: el lector XMI normaliza y luego mapea a una vista previa del documento del editor; la exportación también depende del contrato actual.

## Decisiones arquitectónicas

- Spring Boot conserva autorización, validación y persistencia; FastAPI no escribe directamente en PostgreSQL.
- La validación de persistencia no normaliza ni corrige silenciosamente el documento.
- La concurrencia usa una revisión del registro, no una segunda versión del formato JSON.
- Se conserva compatibilidad con relaciones heredadas mediante `relationshipType`, aunque el contrato actual usa cardinalidades independientes.
- La adopción de Flyway para una base preexistente es deliberada y manual; no se ocultan diferencias de esquema mediante baseline automático.

## Pruebas verificadas el 23 de octubre de 2026

- Frontend: `npm.cmd run test:diagram-ai` — **104 aprobadas, 0 fallidas**.
- Python, clasificación de intención: `python -m pytest -q -p no:cacheprovider tests/agent/test_intent_authorization.py` — **7 aprobadas y 19 subcasos aprobados**.

La suite completa de Python no llegó a ejecutarse en esta computadora: la recolección se detuvo porque el intérprete global no tiene instalado `groq`. Esto es un bloqueo del entorno y no se registra como fallo funcional ni como suite aprobada.

## Pruebas pendientes

- Suite completa de FastAPI dentro del entorno definido por `ai-service/requirements.txt`.
- Pruebas Java de intención, validación, persistencia y concurrencia. El Wrapper falló antes de compilar y el Maven global intentó usar un repositorio local no escribible en `C:\.m2\repository`; no hay resultado de test Java en esta recuperación.
- Suite Java completa y pruebas de integración que requieran PostgreSQL/Docker.
- Verificación explícita del incremento real de `revision` y de dos escrituras concurrentes contra PostgreSQL, no sólo con mocks.

## Riesgos conocidos

- `ARCHITECTURE.md` está desactualizado: afirma que IA, diagramas, colaboración y Flyway no existen. No debe usarse como fuente única del estado actual.
- El cálculo preliminar de tamaño usa la representación `Map.toString()` y no los bytes JSON serializados; el límite efectivo merece una prueba de frontera.
- `GuardarDiagramaRequest` permite `baseRevision` negativo a nivel de Bean Validation; el servicio lo rechazará como conflicto en una actualización, pero el contrato podría expresarse con mayor precisión.
- La captura conjunta de `OptimisticLockingFailureException` y `DataIntegrityViolationException` devuelve un conflicto sin siempre poder informar la revisión actual.
- Los contratos actuales no representan clases UML con operaciones, interfaces, enumeraciones ni semántica de herencia/composición/agregación.

