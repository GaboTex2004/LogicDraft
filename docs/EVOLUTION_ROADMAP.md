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
