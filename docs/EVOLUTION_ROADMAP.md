# Roadmap de evolución de LogicDraft

Este roadmap expresa dirección y dependencias; no declara implementadas capacidades futuras. Cada subfase requiere validación antes de continuar y debe conservar compatibilidad con datos, permisos y consumidores existentes.

## Fase 1 — Estabilización

Estado: implementada en la rama `Antony`, con validación automatizada parcial.

- Unificación semántica de intenciones del agente en React, Java y Python.
- Validación estructural del documento antes de persistir.
- Concurrencia optimista con `revision`/`baseRevision` y HTTP 409.
- Migración V2 y estrategia segura de adopción de Flyway.
- Pendiente: completar las suites Java, FastAPI e integración PostgreSQL descritas en `PHASE_1_SUMMARY.md`.

## Fase 2 — Modelado UML avanzado integrado desde DiagramPlataform

Objetivo: ampliar el editor con clases UML (atributos y métodos), interfaces, enumeraciones, herencia, implementación, composición, agregación, relaciones y multiplicidades, además de mejorar la presentación en React Flow.

Subfases propuestas:

1. Obtener y revisar DiagramPlataform como referencia funcional/visual: modelo, interacciones, notación, layout, licencias y dependencias. Su código no está presente en este clon; no copiar componentes antes de evaluar esos puntos.
2. Definir un contrato UML canónico y una estrategia de versión/migración compatible con documentos `version: 1`.
3. Crear una matriz de compatibilidad para persistencia, validadores, IA, STOMP, `ApplicationSchema`, generadores y XMI.
4. Implementar primero modelo y validación con fixtures de compatibilidad; luego edición/renderizado en React Flow.
5. Adaptar consumidores uno por uno sin degradar el modelado entidad-relación existente.

Criterio de salida: edición y persistencia UML verificadas, documentos anteriores legibles, autorización/concurrencia intactas y contratos de IA, colaboración, generadores y XMI cubiertos por pruebas.

### Estado real al 24 de octubre de 2026

- **2.1 Contrato UML extendido — COMPLETADA**: documentos v1/v2, discriminadores, IDs, visibilidad, métodos, parámetros, literales, relaciones UML, geometría y metadatos EA. Véase `UML_V2_CONTRACT.md`.
- **2.2 Nodos UML — PARCIAL**: clases, interfaces y enumeraciones se crean, renderizan, guardan y reabren; los métodos básicos se editan. Falta edición visual de parámetros, literales, atributos estáticos y abstracción.
- **2.3 Relaciones UML — COMPLETADA para contrato/editor**: asociación, generalización, realización, agregación, composición y dependencia tienen semántica validada y representación diferenciada. No se convierten implícitamente en relaciones de base de datos.
- **2.4 Experiencia visual — IMPLEMENTADA, validación manual pendiente**: toolbox, panel contextual, compartimentos y estilos diferenciados integrados en React Flow; marcadores UML para herencia, realización, dependencia, agregación y composición; nombres y multiplicidades visibles. Quedan por comprobar manualmente accesibilidad, legibilidad en todos los zooms y selección/arrastre en grafos grandes.
- **2.5 IA UML — BLOQUEADA por alcance semántico pendiente**: se conserva la IA segura de entidades, aceptación explícita, permisos y revisión. Aún no se añadieron operaciones UML a los DTO Java, esquemas Pydantic y aplicador React; no se anuncian como soportadas.
- **2.6 Compatibilidad transversal — CIERRE PARCIAL VERIFICADO**: persistencia JSONB, colaboración genérica, revisión y CRUD de entidades se preservan. Los diagramas mixtos añaden clases, interfaces y enums estructurales Java/Dart sin convertirlos en tablas. La exportación XMI incluye UML 2.1 y metadatos visuales EA con geometría. La importación sigue cubriendo un subconjunto y falta validación dentro de EA y contra las muestras QA ausentes; no se declara round-trip universal.

### Verificación del hardening de Fase 2

- Frontend: 104 pruebas existentes aprobadas; las 7 pruebas contractuales UML v2 se ejecutan por separado y están aprobadas. Lint y build de producción aprobados.
- Java consolidado de cierre: 56 pruebas de validación, XMI, mapeo y generadores aprobadas. La selección final de interoperabilidad/generación ejecutó 31 pruebas, con 0 fallos y 1 prueba de fixture EA omitida por ausencia del archivo externo esperado.
- Los esqueletos Java estructurales producidos por un diagrama mixto se compilaron además de forma aislada con `javac` durante la prueba automatizada.
- La regresión 422 de enumeraciones vacías quedó cubierta por una prueba de servicio que guarda y vuelve a leer el documento mixto producido por la UI. La comprobación contra PostgreSQL y el contenedor local del usuario continúa siendo manual para no modificar su base ni ejecutar migraciones.
- FastAPI no fue modificado durante el hardening y no se repitió su suite.
- La compatibilidad visual y la importación del XMI producido en una instalación concreta de Enterprise Architect requieren verificación manual.
- Las muestras requeridas `docs/qa-samples/EA_referencia.xmi`, `LogicDraft_exportado.xmi` y `fullstackPrueba1.zip` no estaban presentes el 24 de octubre de 2026; la comparación contractual específica queda pendiente.

DiagramPlataform se consultó exclusivamente como referencia funcional. No se encontró licencia declarada y no se copió código.

### Verificación de esta entrega

- Frontend: 104 pruebas existentes + 2 pruebas UML v2 aprobadas; lint y build de producción aprobados. Vite mantiene una advertencia no bloqueante por un chunk mayor de 500 kB.
- FastAPI: 152 pruebas y 162 subpruebas aprobadas; sólo se emitieron dos advertencias de deprecación.
- Java focalizado (contrato, mapeo generable e importación EA): 24 pruebas, 0 fallos, 1 omitida.
- Java completo: 196 pruebas intentadas, con 4 fallos y 9 errores ambientales. El esquema local carece de `diagramas.revision`; las pruebas de artefactos temporales no pudieron usar `%TEMP%`/el repositorio Maven y las integraciones opcionales no tenían PostgreSQL de prueba ni Flutter configurados. No se modificó esa base.

## Fase 3 — Agente IA compartido y colaboración

- Evolucionar el contexto y las operaciones estructuradas del agente para el contrato UML validado.
- Definir cómo se comparten propuestas, aceptación/rechazo y autoría entre colaboradores.
- Mantener autorización en Spring Boot, propuestas no mutantes hasta aceptación y eventos idempotentes.
- Resolver conflictos entre revisión REST y eventos STOMP con pruebas multiusuario.

### Avance verificado el 28 de octubre de 2026 — fase NO cerrada

Base inspeccionada: rama `Antony`, commit `259c260a9d2c893c0f306b414f5335aa544f54b0`
(`fase UMLv2 completada`), árbol inicialmente limpio. Los estados históricos anteriores no son
una declaración de que las suites actuales pasaran antes de esta intervención.

#### Auditoría y decisiones

- Los documentos reales soportan `version: 1 | 2`; `revision` sigue siendo `@Version` de JPA.
  `AGENTS.md` y `ARCHITECTURE.md` contienen descripciones históricas incompletas de este estado.
- `DiagramaService.guardar` ya valida documento, membresías y revisión. Se reutiliza como única
  entrada de persistencia; no se creó otra revisión ni se cambió el JSON UML/JSONB.
- Antes, `CollaborationController` retransmitía borradores sin revisión/validación documental;
  el editor podía sustituir un lienzo sucio por un lote remoto y autoguardar cambios recibidos.
  `DIAGRAM_SAVED` no reconciliaba revisiones y reconectar no recuperaba el documento.
- `AgentProjectContextService` emparejaba por índice listas UML filtradas con el documento original.
  Ahora resuelve IDs por identidad semántica y mantiene los IDs originales de relaciones.
  Esto NO añade soporte de métodos/interfaces/enumeraciones a las operaciones IA existentes.
- El agente sigue teniendo historial, propuesta y aceptación locales. Java valida intención,
  permisos y operaciones propuestas; `/authorize-edit` no aplica una propuesta en servidor.
  FastAPI sigue sin persistir y no se modificaron proveedores ni prompts.

#### Contrato de sincronización implementado (3.1–3.2)

1. El editor conserva cambios locales y usa el PUT existente (autoguardado de 1,5 segundos).
   Envía `baseRevision` y un `clientId` opcional; éste sólo correlaciona el navegador, nunca
   identifica al usuario ni concede autorización. Clientes REST anteriores pueden omitirlo.
2. Tras `saveAndFlush`, Spring agenda `DIAGRAM_COMMITTED` y lo entrega sólo después del commit.
   Su `eventId` es `diagram:<id>:<revision>`; payload: `{ diagramId, revision }`. La autoría se
   obtiene del usuario autenticado. No se difunde el contenido del diagrama por STOMP.
3. Los clientes ya no publican mutaciones STOMP. El servidor rechaza eventos de nodos, relaciones,
   lotes, guardados o commits enviados por clientes. Mantiene PING y presencia autorizados.
   Frontend y backend deben actualizarse juntos; una pestaña con el cliente anterior debe recargarse.
4. Las notificaciones se deduplican y provocan GET autorizado. Se ignoran revisiones iguales o
   anteriores, también para el emisor. Un lienzo limpio recibe el documento completo de una vez.
   Si hay cambios locales o arrastre, se conserva el lienzo y la revisión base y se informa conflicto.
   No hay combinación automática de ediciones independientes, CRDT ni OT.
5. Al reconectar, recuperar foco/conectividad o cada 15 segundos con pestaña visible se consulta
   el servidor. Las lecturas se coordinan con PUT en curso y se descartan al desmontar el editor.
   No hay log durable de eventos ni garantía de reentrega del broker; REST recupera el estado.
6. Un conflicto detiene nuevos guardados automáticos. El usuario puede descargar su borrador JSON
   antes de confirmar una recarga que lo reemplaza. No se añadió importador de borradores JSON.
7. La entrega STOMP revalida membresía del suscriptor; desuscribir y desconectar limpian presencia.
   Se conserva el control tenant/workspace/proyecto y roles existentes, sin bypass global.
8. Una consulta IA no convierte su respuesta tardía en una propuesta vigente si cambió el lienzo;
   la aceptación local vuelve a sincronizar y comprobar permisos. Falta la aceptación semántica
   transaccional en servidor: no se presenta este avance como agente compartido completo.

#### Estado de subfases

| Subfase | Estado y límite |
|---|---|
| 3.1 Contratos | Base implementada y probada para commits de diagrama. Pendiente identidad/estado durable de propuestas. |
| 3.2 Colaboración | Implementada reconciliación conservadora y seguridad; pruebas de servicio y broker en memoria aprobadas. Pendiente dos navegadores y PostgreSQL de prueba. |
| 3.3 Agente compartido | Detenida antes de cambiar el contrato persistido; requiere aprobación del diseño aditivo siguiente. |
| 3.4 UX | Autoría de commits recibidos, estado de sincronización, conflicto y descarga de borrador. Pendiente UX de propuestas compartidas. |
| 3.5 Pruebas | Cobertura de concurrencia, duplicados, reconexión, roles, aislamiento y regresiones ampliada; faltan aceptación/rechazo compartidos y E2E reales. |

#### Decisión pendiente de aprobación para 3.3 (NO implementada)

V1/V2 sólo contienen usuarios, tenants, workspaces, membresías, proyectos y diagramas.
No hay tablas de conversaciones/propuestas. Reutilizar `diagramas.contenido` mezclaría historial
con UML y alteraría su contrato; `proyectos.descripcion` tampoco representa una conversación.

Se propone una migración aditiva V3 para sesiones por proyecto, mensajes con autor y propuestas
estructuradas con UUID, revisión base, estado y usuario/fecha de aceptación o rechazo. El diseño
debe incluir claves foráneas, unicidad de operación y transición condicional de PENDING a un estado
terminal. Aceptar debe revalidar acceso/revisión, aplicar operaciones en Java, validar el documento
completo y guardar diagrama + estado de propuesta en la misma transacción. Un reintento no debe
volver a aplicarla; una propuesta obsoleta debe quedar inválida sin tocar el diagrama.

Esto requiere aprobación del nuevo contrato persistente. No se creó ni ejecutó V3. Tras aprobar,
faltan diseño detallado, pruebas de migración/compatibilidad y pruebas de aceptación simultánea,
rechazo, invalidez e historial compartido. No se debe migrar la base existente sin autorización y
respaldo. También queda extender coordinadamente el subconjunto de operaciones UML en Java,
TypeScript y Python; no anunciar capacidades que los DTO/esquemas actuales no representan.

#### Evidencia de pruebas de esta entrega

- Frontend: `npm.cmd run test:diagram-ai`, **125 aprobadas**. El script ahora incluye las pruebas
  UML v2 y sincronización; `npm.cmd run build` y `npm.cmd run lint` aprobados. Persiste aviso de
  chunk de producción mayor a 500 kB.
- FastAPI: `.\.venv\Scripts\python.exe -m pytest -q`, **152 aprobadas + 162 subpruebas**;
  dos avisos de deprecación y un aviso de permisos de caché. Ejecutado antes de modificar código;
  Python no cambió en esta intervención.
- Java: **212 ejecutadas, 209 aprobadas, 0 fallos, 0 errores, 3 omitidas** por fixtures EA externos.
  Comando desde `backend/`:

  ```powershell
  mvn "-Dtest=*Test,!BackendApplicationTests,!ApplicationSchemaSecurityTest,!SpringGenerationSecurityTest,!ProjectAgentSecurityTest,!Generated*Test" test
  ```

- Se excluyeron deliberadamente los contextos completos que cargarían la base configurada y las
  pruebas `Generated*Test` de herramientas/servicios externos. No equivale a aprobar `mvn test`
  completo. El Wrapper falla con `NullArray` / `Cannot start maven from wrapper`; Maven instalado
  necesitó permiso para usar la caché del usuario (el sandbox intentaba `C:\.m2\repository`).
- Las nuevas pruebas incluyen dos hilos usando servicio, autorización y validador reales con
  repositorio CAS simulado; transacciones Spring para commit/rollback; y broker/canales Spring
  reales en memoria sin servidor de red ni PostgreSQL. No son una verificación de JPA sobre una
  base real ni de dos navegadores.
- Las regresiones cubren UML, asociaciones, persistencia, XMI, Spring Boot, Fullstack y Flutter.
  Se corrigieron expectativas antiguas de tests (CSS, representación derivada, acumulación de
  aserción XMI y serialización DATE de Flutter); no se modificaron generadores ni código XMI.
- `git diff --check` se ejecuta antes de entregar. No se hicieron commits, push, migraciones,
  cambios Docker/AWS, lectura de secretos ni cambios en DiagramPlataform.

#### Comprobación manual pendiente

1. Usar frontend/backend actualizados, dos perfiles de navegador con cuentas OWNER y EDITOR del
   mismo workspace y un proyecto de prueba. Confirmar igual contenido inicial.
2. Editar clase, atributos y relación con una cuenta; esperar guardado. La otra debe actualizarse
   sin autoguardar de nuevo. Comprobar autoría cuando llegue la notificación.
3. Editar simultáneamente: debe ganar una revisión; el otro lienzo debe conservar su borrador,
   mostrar conflicto y permitir descargarlo. Confirmar recarga sólo después de resguardarlo.
4. Desconectar una pestaña, guardar desde la otra y reconectar: si estaba limpia debe recuperarse;
   si tenía cambios locales debe conservarlos e indicar conflicto.
5. Verificar VIEWER sin escritura, proyecto de otro workspace/tenant sin lectura/suscripción y
   revocación de membresía con conexión abierta.
6. Consultar IA y cambiar el diagrama mientras responde: la propuesta obsoleta no debe aceptarse.
   Confirmar que propuestas/historial aún NO se comparten entre usuarios.
7. Reabrir UML con clase asociativa y exportar/importar XMI en EA; comprobar generación existente.
   La visualización real en EA y aplicaciones generadas no se ejecutó aquí.

## Fase 4 — Generación fullstack y mejoras Flutter con IA local en dispositivo

- Extender el `ApplicationSchema` sólo después de decidir qué construcciones UML son generables y cuáles son exclusivamente documentales.
- Evolucionar generación Spring Boot/Flutter con compatibilidad hacia los diagramas actuales.
- Diseñar la IA local en dispositivo considerando tamaño, memoria, latencia, privacidad, plataforma y distribución del modelo; no descargar ni incorporar modelos durante fases anteriores.
- Validar artefactos generados mediante compilación, análisis y pruebas reproducibles.

## Fase 5 — Interoperabilidad EA, generación React y pruebas completas

- Ampliar importación/exportación XMI con round-trip documentado para los elementos UML soportados.
- Incorporar generación React alineada al mismo esquema canónico usado por Spring Boot y Flutter.
- Completar pruebas unitarias, contractuales, integración, concurrencia, seguridad, XMI, generación y end-to-end.
- Documentar degradaciones controladas cuando EA u otro consumidor no pueda representar una extensión.

## Fase 6 — Preparación del despliegue AWS

- Evaluar topología, configuración por entorno, secretos, almacenamiento, red, TLS, observabilidad, respaldo y recuperación.
- Definir estrategia de migración Flyway y despliegue sin pérdida de datos.
- Verificar imágenes, health checks, límites de recursos y dependencias de proveedores IA.
- Preparar runbooks y criterios de rollback. La modificación o ejecución sobre AWS requiere un alcance y autorización separados.

## Dependencias técnicas previas a Fase 2

- Acceso al repositorio o documentación de DiagramPlataform, incluida su licencia y versiones de dependencias.
- Inventario de elementos UML y comportamiento visual que se desea adoptar, diferenciando referencia de reutilización de código.
- Decisión de contrato: discriminantes de nodos/edges, atributos y operaciones, firmas, visibilidad, estereotipos, tipos de relación, roles y multiplicidades.
- Estrategia para leer documentos v1 y escribir una versión nueva sin romper diagramas existentes.
- Reglas de mapeo UML → `ApplicationSchema`: qué generan Spring/Flutter y qué se preserva sólo como metadato.
- Extensión coordinada de esquemas TypeScript, DTO/validación Java y modelos Pydantic/prompts/operaciones Python.
- Evolución de payloads colaborativos para cambios UML atómicos, manteniendo deduplicación y `revision`.
- Matriz XMI para clases, interfaces, enumeraciones, generalización, realización, agregación, composición y multiplicidades.
- Fixtures compartidos y pruebas contractuales entre las tres capas antes de modificar UI.

## Riesgos antes de integrar UML

- Ampliar el JSON v1 en sitio puede hacer que validadores o consumidores antiguos rechacen o ignoren datos, produciendo pérdida silenciosa.
- `ApplicationSchemaMapper` presupone nodos entidad con atributos persistibles; métodos, interfaces, enums y herencia necesitan semántica explícita de generación.
- Los clasificadores y operaciones IA actuales sólo conocen operaciones entidad/atributo/relación; prompts y validadores podrían inventar o descartar elementos nuevos.
- Los eventos STOMP actuales deben preservar operaciones compuestas de forma atómica y coexistir con conflictos de revisión REST.
- XMI tiene variantes por versión/herramienta; una equivalencia visual no garantiza round-trip semántico con Enterprise Architect.
- React Flow puede representar formas nuevas, pero handles, selección, edición, layout, accesibilidad y rendimiento deben diseñarse para grafos mixtos.
- Copiar desde DiagramPlataform sin revisar licencia, dependencias y contratos puede introducir incompatibilidades o deuda técnica.
- La documentación histórica, en particular `ARCHITECTURE.md`, contiene afirmaciones obsoletas y puede inducir decisiones incorrectas si no se contrasta con el código.
