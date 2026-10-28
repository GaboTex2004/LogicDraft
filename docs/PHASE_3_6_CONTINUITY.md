# Fase 3.6 — Continuidad: seguridad semántica del agente

Fecha: 2026-10-28. Repositorio: `D:\Universidad\Sw1\Examen`, rama `Antony`.
HEAD observado al inicio y al cierre: `d9d8c16` (`WIP fase 3 - avances Codex pendientes de validar`).

## Estado de entrega

Checkpoint estable del bloque crítico de **etapa A**, no cierre de Fase 3.6 ni de Fase 3.
El proyecto tenía modificaciones y archivos sin seguimiento de Antigravity antes de esta sesión,
incluidos los servicios de conversaciones y sus pruebas. Se conservaron; no se realizaron commits.

| Etapa | Estado exacto |
|---|---|
| A: seguridad destructiva | Implementada y validada automáticamente. Una solicitud de borrar/renombrar un atributo no autoriza borrar su clase; PK no autoriza borrar/consumir relaciones. Revalidación al aceptar propuestas antiguas. |
| A/B: CLASS frente a ENTITY | Protección conservadora: una creación explícita `CLASE UML`, `CLASS`, interfaz, enumeración o clase asociativa no puede pasar como `ADD_ENTITY`. Se rechaza y pide aclaración/uso del editor. **No se implementó creación CLASS**. El uso histórico genérico de “clase” sigue siendo compatible con ENTITY y requiere corregirse en B. |
| B: CRUD UML completo | Pendiente. No se añadieron tipos, operaciones ni campos de contrato. |
| C: planes completos y confirmaciones | Pendiente. Se preserva aplicación atómica existente; no se implementó un plan de intención completo ni resolución de propuestas por “Sí”. |
| D: concurrencia perro/gato | Auditoría de IDs realizada; reproducción con finalización inversa pendiente. No se atribuye el problema a STOMP ni al proveedor sin esa prueba. |
| E: prompts y precisión de llama3.2 | Pendiente. No se cambió el proveedor, el modelo, sus opciones ni los prompts. La nueva barrera impide reparar respuestas destructivas inseguras. |

El usuario informa que Flyway V3 ya se aplicó y que la colaboración 3.2 funciona manualmente.
No repetir migraciones por la mención histórica de “V3 pendiente” en el roadmap.
El código actual admite documentos v1/v2; el párrafo v1 de AGENTS.md es histórico.

## Auditoría focalizada y diagnóstico

1. **Borrado de atributo que elimina clase (causa confirmada en código y reproducida en pruebas):**
   `ai-service/app/skills/diagram/completeness.py::_sanitize_requested_operations` construía
   `deleted_entity_keys` a partir de operaciones del modelo cuando encontraba cualquier verbo
   de eliminación y no un nombre de entidad explícito. Así, “elimina de Producto el atributo…”
   conservaba `DELETE_ENTITY Producto`. `AgentIntentClassifier` sólo distinguía modificación,
   información o ambigüedad. `ContextualOperationValidator` validaba existencia y estructura,
   pero no recibía el texto del usuario. Una eliminación estructuralmente válida podía acabar PENDING.
2. **CLASS/ENTITY:** `EntityDefinition` contiene nombre y atributos, sin `kind` en Java/Python.
   `ServerDiagramOperationApplier` crea una entidad por defecto y el resumen dice “Crear entidad”.
   El mapper reciente sí proyecta CLASS y ASSOCIATION_CLASS existentes: esa corrección se conservó.
3. **Instrucciones múltiples:** FastAPI usa expresiones de completitud parciales y una reparación,
   seguida de `_merge_previous_and_repaired`. No es un plan completo de todas las acciones del usuario.
   Esta sesión no garantiza detectar toda acción omitida. Se elimina la aceptación de lotes que
   contienen eliminaciones sin autorización, incluso durante la reparación.
4. **Confirmaciones:** `AgentService` usa historial textual; el servicio compartido no resuelve
   inequívocamente una propuesta del solicitante a partir de “Sí” o “agrega los cambios…”.
   No se añadió aceptación por lenguaje natural. Aceptar/Rechazar sigue siendo el flujo seguro.
5. **Solicitudes simultáneas:** `SharedAgentConversationService` conserva variables por invocación,
   `mensajeOrigen`, `solicitadoPor`, conversación y propuesta vinculada al mensaje del agente.
   `ConversationContextSelector` selecciona hasta 10 mensajes SENT anteriores al ID de origen,
   con autoría textual para humanos. React conserva `mensajeOrigenId` y actualiza propuestas
   por `messageId`, pero ordena mensajes por ID y muestra “en respuesta a [nombre]”, no un hilo
   visual anclado al mensaje original. El indicador de actividad usa una única entrada por conversación.
   Esto identifica puntos a probar, **no demuestra la causa del intercambio perro/gato**.
6. **Recomendación duplicada de asociación:** el contexto ya incluye `associations`, extremos e IDs;
   el prompt ya pide comprobarlas. La recomendación incorrecta no prueba que falte transporte de datos.
   Hay limitaciones reales: no se transmite `kind` en AgentEntity; el resumen de UML sólo consultable
   se trunca a 500 caracteres y el proveedor estructurado limita `num_predict` a 1200. Su efecto
   sobre casos reales debe evaluarse en B/E, no se afirma como causa demostrada.

## Contrato de seguridad añadido

- Java: `SemanticOperationValidator.validate(message, context, operations)`.
- Python: `validate_semantic_safety(prompt, diagram, response)`.
- Gramática acotada de órdenes completas + nombres exactos normalizados + resolución única en el
  contexto. No basta encontrar “elimina”. Acepta nombres compuestos y nombres entre comillas.
- Se valida **todo el lote antes** del saneamiento y de generar una propuesta aceptable.
  No se sustituyen operaciones ni se ejecuta automáticamente una alternativa.
- `DELETE_ENTITY` requiere una clase/entidad explícitamente indicada y existente; las asociativas
  requieren `DELETE_ASSOCIATION`, con semántica explícita. El applier mantiene las cascadas
  estructurales existentes para una eliminación legítima de un extremo.
- `DELETE_ATTRIBUTE` debe señalar atributo y propietario; `DELETE_RELATIONSHIP` debe señalar
  extremos y, si hace falta, nombre que desambigüe. Duplicados, nombres ausentes, condicionales,
  negaciones y referencias vagas se rechazan. Una cláusula entre comillas no concede autorización.
- Crear/convertir una asociación también puede consumir una N:M existente en el applier:
  se exige una orden explícita de esa acción; actualizar PK no la autoriza.
- Se conserva el descarte de operaciones en consultas del agente. La ruta de interpretación
  mantiene la respuesta vacía existente para consultas reconocidas.
- FastAPI devuelve HTTP 422 por `SemanticSafetyError`, sin intento de reparación de ese lote.
  Las reparaciones de incompletitud no peligrosa siguen limitadas a una llamada adicional.
- Java rechaza con HTTP 422, categoría `OPERATION_VALIDATION_ERROR`, detalle técnico estable
  `SEMANTIC_TARGET_MISMATCH`. En el chat compartido el resultado se registra como respuesta
  FAILED y se conserva el mensaje humano; no implica que el endpoint de chat responda HTTP 422.
- El cliente Java no expone cuerpos arbitrarios de errores upstream. Su mensaje genérico 422
  ahora pide aclarar tipo, objetivo y acción, sin afirmar que se aplicaron cambios.
- Al aceptar una propuesta PENDING se contrasta otra vez con su mensaje humano de origen y
  el documento actual, después de verificar revisión. Una incompatibilidad deja INVALID y
  no guarda el diagrama. Un conflicto de revisión conserva STALE.
- **Sin cambios en DTOs JSON Java/Python/TypeScript, documentos persistidos, revisión, STOMP,
  tablas o migraciones.** TypeScript no se editó en esta sesión.

Límite deliberado: no es comprensión universal del español. Las elipsis (“elimínala”), múltiples
órdenes sin delimitadores claros, dependencias de renombrados previos en el mismo lote y frases
con negaciones/condiciones pueden requerir reformulación. No ampliar heurísticas para hacer pasar
un caso sin pruebas espejo Java/Python y sin verificar el objetivo. La seguridad no sustituye la
completitud global pendiente en C ni la validación estructural ya existente.

## CRUD actual (no confundir con generadores)

Las 15 operaciones existentes se conservan:

- `ADD_ENTITY`, `DELETE_ENTITY`, `RENAME_ENTITY`.
- `ADD_ATTRIBUTE`, `DELETE_ATTRIBUTE`, `RENAME_ATTRIBUTE`, `CHANGE_ATTRIBUTE_TYPE`,
  `SET_ATTRIBUTE_PRIMARY_KEY`, `SET_ATTRIBUTE_NULLABLE`.
- `ADD_RELATIONSHIP`, `DELETE_RELATIONSHIP`, `UPDATE_RELATIONSHIP`.
- `CREATE_ASSOCIATION`, `DELETE_ASSOCIATION`, `CONVERT_MANY_TO_MANY_ASSOCIATION`.

Se verificó borrar `precio de venta` de CLASS Producto tras aceptación, conservando todo el resto
del documento UML v2, incluida compra. Se verificó la eliminación legítima de Producto con su
asociativa dependiente sin borrar Venta. No se promete que el LLM siempre elija la operación correcta:
si se equivoca, la propuesta peligrosa se rechaza.

INTERFACE/ENUMERATION y sus métodos/literales tienen representación de consulta limitada en el
contexto, sin operaciones CRUD del agente end-to-end. No se crean simulándolos con ADD_ENTITY.
El contrato actual de relaciones del agente no tiene todas las variedades `relationKind` del editor.
No se modificaron los generadores Spring Boot/Flutter/Fullstack ni XMI.

## Archivos editados por esta sesión

Producción:

- `ai-service/app/skills/diagram/semantic_safety.py` (nuevo).
- `ai-service/app/skills/diagram/completeness.py`.
- `ai-service/app/api/routes/agent.py`.
- `ai-service/app/api/routes/diagram.py`.
- `backend/src/main/java/com/sw1/backend/ai/diagram/validation/SemanticOperationValidator.java` (nuevo).
- `backend/src/main/java/com/sw1/backend/ai/agent/service/ContextualAgentService.java`.
- `backend/src/main/java/com/sw1/backend/ai/service/ContextualDiagramAiService.java`.
- `backend/src/main/java/com/sw1/backend/ai/client/AiServiceClient.java`.
- `backend/src/main/java/com/sw1/backend/ai/conversation/service/SharedAgentConversationService.java`
  (ya existía sin seguimiento; sólo se agregó la barrera en aceptación e imports).

Pruebas/documentación:

- `backend/src/test/resources/ai/semantic-safety-cases.json` (nuevo; 36 casos compartidos).
- `backend/src/test/java/com/sw1/backend/ai/agent/SemanticOperationValidatorTest.java` (nuevo).
- `backend/src/test/java/com/sw1/backend/ai/conversation/UmlV2AgentContextAndProposalRegressionTest.java`
  (ya existía; cuatro regresiones nuevas con cliente HTTP/servicios reales y repositorios simulados).
- `ai-service/tests/agent/test_semantic_safety.py` (nuevo).
- `ai-service/tests/diagram/test_robustness.py`: el caso anterior esperaba filtrar dos borrados
  inventados durante reparación y aceptar el resto. Ahora exige rechazar esa reparación;
  conserva las aserciones del resultado correcto con una reparación legítima separada.
  Es un cambio explícito del contrato de seguridad, no relajación de aserciones.
- `docs/PHASE_3_6_CONTINUITY.md` (este archivo).

Otros archivos dirty del repositorio pertenecen al trabajo anterior; no atribuirlos a esta entrega.

## Pruebas ejecutadas

| Suite | Línea base actual, antes de editar | Final |
|---|---|---|
| Frontend `test:diagram-ai` | 144 aprobadas | Mismo código frontend; 144 aprobadas |
| Frontend lint/build | No ejecutadas antes de editar | Ambas aprobadas; warning bundle >500 kB |
| Python pytest | 152 + 162 subpruebas aprobadas | 191 + 162 subpruebas aprobadas |
| Java, selección aislada indicada abajo | 249 ejecutadas, 0 fallos/errores, 3 omitidas | 289 ejecutadas, 0 fallos/errores, 3 omitidas |
| `git diff --check` | Aprobado | Aprobado |

La suite focalizada intermedia Java pasó 63 pruebas antes de añadir seis vectores adicionales de
seguridad. Las regresiones generales finales incluyen esos seis. Los 36 vectores se ejecutan en
ambos lenguajes. Se detectaron y corrigieron fallos durante el desarrollo; estos resultados son
de las ejecuciones posteriores a las correcciones, no de la primera ejecución.

El Wrapper falló antes de iniciar Maven: `NullArray / Cannot start maven from wrapper`.
Se utilizó Maven instalado con caché del usuario. No se ejecutó `mvn test` sin filtro.
Las tres omisiones corresponden a fixtures externos de EA según la suite existente.
No se ejecutaron pruebas de SpringBootTest que arrancan el datasource configurado ni pruebas
Generated* con PostgreSQL/Flutter/Maven externo. No afirmar que esa integración completa pasó.
Python presenta dos advertencias de deprecación Starlette/httpx. Se deshabilitó únicamente la
caché de pytest en la ejecución final porque su directorio local devolvía acceso denegado.

Regresiones existentes ejecutadas: cursores/presencia, locks, merge, revisiones, seguridad de canales,
conversaciones, propuestas, UML v1/v2, asociaciones, mapeo/generación y XMI disponibles sin externos.
Esto preserva evidencia automatizada de Fase 2/3.2; **no equivale a una nueva prueba manual en EA,
dos navegadores, Ollama real ni aplicaciones generadas ejecutándose**.

## Cobertura de los 16 casos solicitados

| Casos | Evidencia y trabajo restante |
|---|---|
| 1, 11 | Cubiertos: DELETE_ATTRIBUTE correcto; DELETE_ENTITY malicioso rechazado en Java y Python; integración del chat conserva mensaje y documento. |
| 2 | Cubierto rechazo de DELETE_ENTITY ante rename y contrato de RENAME_ATTRIBUTE; interpretación real de llama3.2 pendiente. |
| 3, 4 | Creación CLASS pendiente B; caso 3 ahora falla seguro en vez de crear ENTITY. No declarar ambos resueltos. |
| 5 | Bare `elimina categoria` permitido en vectores; cascada legítima Producto/compra conserva Venta en integración. |
| 6 | Se conserva suite N:M existente; no nueva validación manual de la frase exacta. |
| 7 | Se conserva contexto/consulta sin mutaciones; exactitud de explicación real sobre compra pendiente E. |
| 8, 9 | Los vectores rechazan eliminaciones inventadas; resolución de propuesta/clarificación pendiente C. |
| 10 | Pendiente D: prueba concurrente perro/gato con respuestas invertidas. |
| 12, 13 | Suites existentes de IDs/JSON inválido y preservación de mensajes aprobadas; no prueba de proveedor real. |
| 14 | Sólo completitud parcial existente y rechazo de reparaciones destructivas; plan global pendiente C. |
| 15 | Regresiones existentes STALE/revisión aprobadas; cruce de generaciones sobre distintas revisiones pendiente D. |
| 16 | Se conserva suite UML v2; nueva prueba DELETE_ATTRIBUTE compara documento completo restante. CRUD completo pendiente B. |

## Comandos de verificación y arranque

Desde `D:\Universidad\Sw1\Examen`, PowerShell:

```powershell
cd Frontend
npm.cmd run test:diagram-ai
npm.cmd run lint
npm.cmd run build
cd ..\ai-service
.\.venv\Scripts\python.exe -m pytest -q -p no:cacheprovider
cd ..\backend
mvn "-Dtest=*Test,!BackendApplicationTests,!ApplicationSchemaSecurityTest,!SpringGenerationSecurityTest,!ProjectAgentSecurityTest,!Generated*Test" test
cd ..
git diff --check
```

Comando integrado conocido, **no ejecutado en esta sesión**. Usarlo sólo sobre el despliegue local
previsto y con el respaldo/política de datos habitual; no reconstruir una base para validar este cambio:

```powershell
docker compose --env-file .env.deploy -f compose.deploy.yml up -d --build
```

No se leyó `.env.deploy`; no se tocaron Docker/AWS, credenciales, modelos, dependencias, BD real,
DiagramPlataform, generadores, XMI, bloqueos ni transporte. No hubo commits, pushes o descartes.

## Validación manual y próximo trabajo

Usar un proyecto de prueba, nunca una copia irrecuperable del proyecto real:

1. Gabriel: con Producto/`precio de venta`, Venta y compra guardados, pedir
   `@LogicDraft elimina de Producto el atributo precio de venta`. Debe aparecer sólo DELETE_ATTRIBUTE
   o un rechazo claro. Nunca aceptar DELETE_ENTITY. Antes de aceptar no debe variar la revisión.
   Aceptar una propuesta correcta y recargar: conservar clases, compra, atributos restantes y relaciones.
2. Carlos bloquea Producto mientras Gabriel acepta: conservar el conflicto existente, sin bypass.
   Liberar el bloqueo y repetir con revisión vigente. Verificar VIEWER sin aceptación/escritura.
3. En una copia descartable, pedir eliminar explícitamente una clase. Revisar el impacto estructural
   antes de aceptar; las clases independientes deben permanecer.
4. Una creación `CLASE UML Devolucion` debe rechazar la degradación actual. Resolver creación real en B.
5. Para D: Carlos pide perro y Gabriel gato casi a la vez en la misma conversación. Registrar
   `clientMessageId`, IDs humanos, `mensajeOrigenId`, solicitante, propuesta y revisiones. Forzar orden
   inverso con proveedor simulado en prueba automatizada. Comprobar también reintentos concurrentes,
   indicador de actividad y el lugar visual de cada respuesta; no dar esta prueba por realizada aquí.
6. Volver a comprobar cursores, movimiento remoto, edición de elementos independientes y locks.
   Si se desea cierre manual de Fase 2, exportar/importar una copia en EA; no se verificó EA real aquí.

Retomar por B: diseñar `kind` compatible de extremo a extremo (Java, Python, TypeScript y applier),
conservar `generateCrud`, metadatos y asociaciones. Después C: plan estructurado completo, objetivos,
dependencias, precondiciones/revisión y confirmaciones vinculadas al solicitante. Después D con
concurrencia controlada. Finalmente E: prompts, contexto completo y evaluación real de llama3.2.
No quitar la barrera semántica para mejorar artificialmente la tasa de respuestas del modelo.
