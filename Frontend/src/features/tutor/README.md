# Instructor de LogicDraft — E1 y E2

Módulo independiente de `features/agent` y de la barra de IA. E1 aporta contenido y contratos; E2 monta una burbuja y un panel para explorar el catálogo en las rutas autenticadas. Todavía no hay resaltado, narración ni motor de pasos. No se hacen llamadas HTTP, a modelos ni escrituras de negocio.

## Reglas de implementación acordadas

- Mantener un módulo propio en frontend y, sólo cuando sean necesarios, módulos `tutor` propios en Spring y FastAPI. E1 no necesita servicios nuevos ni carpetas vacías en esos proyectos.
- Conservar las funcionalidades existentes, contratos de diagrama, permisos y comportamiento colaborativo.
- Terminar cada tarea con sus comprobaciones y entregar al usuario para que pruebe y decida si pide reparación, continuación o commits. No avanzar automáticamente a otra etapa ni crear commits/push.
- Reutilizar componentes adecuados del proyecto. Si se necesitan componentes específicos, colocarlos en `features/tutor/components/`.
- Respetar diseño y colores existentes. E1 no añade UI ni estilos. Antes de E2 revisar `AppIcon` y estilos existentes; no introducir una paleta alternativa.
- Incorporar futuras reglas al plan y a este documento cuando se acuerden.

## Archivos y uso

- `types/tutorial.types.ts`: contratos de catálogo, pasos, contexto, evidencia, progreso y recorrido compuesto. Reutiliza tipos de atributos/cardinalidades existentes.
- `content/targets.ts`: inventario de controles y archivo fuente comprobado. Los IDs son lógicos; **no son selectores DOM**. `integration: pending` indica que falta registrar refs en E3.
- `content/catalog.ts`: once tutoriales reutilizables y sus condiciones declarativas.
- `content/firstDiagram.ts`: ejercicio de bienvenida con trece instancias, dos clases, atributos, clave, asociación, cardinalidad y guardado. No se ejecuta todavía.
- `content/messages.ts`: mensajes cerrados para límites, aclaraciones y fallos.
- `services/validateTutorialCatalog.ts`: validación de autoría, no ejecución. Detecta referencias rotas, ciclos, bindings sin resolver, parámetros inválidos y colisiones en el recorrido.

Verificación desde `Frontend`:

```powershell
npm.cmd run test:tutor
npm.cmd run test:diagram-ai
npm.cmd run build
npm.cmd run lint
```

## Catálogo v1

| ID | Pantalla de ejecución | Entradas que debe elegir/obtener el motor |
| --- | --- | --- |
| `choose_workspace` | Dashboard | Workspace autorizado elegido por usuario |
| `create_project` | Proyectos | Workspace y nombre de proyecto |
| `open_editor` | Proyectos | Workspace y proyecto elegido |
| `create_class` | Editor | Proyecto; crea un nodo CLASS |
| `create_entity` | Editor | Proyecto; crea un nodo ENTITY |
| `rename_node` | Editor | Proyecto, nodo y nombre |
| `add_attribute` | Editor | Proyecto, nodo, nombre y tipo |
| `set_primary_key` | Editor | Proyecto, nodo y atributo perteneciente al nodo |
| `create_relationship` | Editor | Proyecto y dos nodos distintos |
| `configure_cardinality` | Editor | Proyecto, relación y cardinalidades de ambos extremos |
| `verify_save` | Editor | Proyecto y revisión local que se necesita confirmar |

Los `prerequisites` son guías de navegación condicionales, no una orden de repetir tutoriales completados ni de crear proyectos automáticamente. `open_editor` no depende de crear proyecto: si existe uno, se elige; si no existe, se ofrece `create_project`. El planificador de E5/E6 deberá resolver estas alternativas y pedir datos faltantes.

## Reglas obligatorias para el motor futuro

1. `runtimeAvailable: false` evita anunciar el catálogo como guía ejecutable. E2 permite consultar sus fichas con un aviso explícito; no habilitar el inicio de recorridos hasta implementar adaptadores, permisos observados y registro real de controles.
2. La bienvenida empieza con un workspace elegido (`chosenWorkspace`), no con un ID inventado. `client`, `order`, etc. son slots del ejercicio: los eventos locales producen los IDs reales. Dos creaciones del mismo tutorial conservan identidad propia.
3. Los parámetros son explícitos. No utilizar nombres del ejercicio como valores por defecto para solicitudes libres. Interpolar como texto, nunca HTML; convertir cardinalidades a las etiquetas del servicio existente `CARDINALITY_LABELS` antes de narrarlas. Tipos visibles del editor: `INTEGER`, `VARCHAR`, etc., no aliases del modelo.
4. `created` requiere un evento local posterior al inicio del paso, dentro del proyecto correcto, y el elemento presente en el estado actual. Para proyecto requiere respuesta API exitosa; para nodo/atributo/arista verifica IDs y pertenencia. No completar por mera coincidencia de nombre ni por eventos remotos.
5. Los outputs se capturan sólo después de completar el paso. Los pasos posteriores deben usar esos IDs. Una arista debe unir exactamente `sourceNode` y `targetNode`, y ser una asociación. La elección inicial debe rechazar nodos incompatibles, referencias ajenas, relaciones estructurales y bloqueos de otros usuarios.
6. `control-ready` exige control montado, visible, contextualizado y habilitado. Puede satisfacerse si el panel ya está abierto. No asumir que un clic o un temporizador abrió un panel.
7. `selected` exige el ID ligado al paso; `field-matches` exige estado actual del mismo objeto y valor esperado. Si ya coincide, distinguir «ya satisfecho» de una edición realizada durante el ejercicio.
8. `saved-revision` espera confirmación del servidor que cubra las revisiones locales pendientes. Un clic en Guardar, fin del audio, mensaje remoto DIAGRAM_SAVED o ausencia de error no bastan. Una respuesta obsoleta que no cubra cambios posteriores no completa el paso. Errores 409/422 y pérdida de permisos pausan.
9. Contexto, bindings, timestamps y roles observados por React no reemplazan autorización del backend. Aislar por usuario/proyecto, deduplicar eventos y limpiar evidencia al cambiar de sesión. Estado de progreso no se guarda en el documento.
10. Las relaciones se ajustan mediante origen/destino reales; no inferir dirección por posición visual. Reutilizar los tipos/labels del editor. La lección no interpreta herencia como asociación.
11. Documentos v1 permanecen soportados; crear un nodo UML utiliza la transición existente del editor a v2. El tutor no cambia `version`, `revision` o `baseRevision`. La clave de una clase UML no activa por sí sola «Generar CRUD»: ese control queda fuera de esta lección.
12. «Anterior» no deshace datos; «Omitir» no produce outputs. Si falta una salida necesaria, pausar y pedir selección o completar el prerrequisito. No seguir con un ID ausente.

La validación estática comprueba consistencia del contenido, no demuestra que esos adaptadores existan ni que las interacciones funcionen en navegador. Las pruebas end-to-end y de voz pertenecen a E3/E4/E7.

## Revisión visual pendiente del usuario

E2 incorpora `components/`, `hooks/useDraggableBubble.ts`, servicios de posición y `tutor.css`. `AuthenticatedLayout` mantiene una sola instancia entre rutas y la desmonta al salir de las rutas autenticadas. Se reutiliza `AppIcon` con un icono de robot. No requiere dependencias nuevas, modelos ni cambios en backend/ai-service.

La posición se guarda como preferencia visual versionada en `localStorage`, sin identidad ni progreso. Se limita al viewport al cargar y redimensionar. El panel es no modal y cede prioridad a los diálogos modales existentes. Arrastrar no debe abrir el panel; F2 activa movimiento con flechas, Intro confirma y Escape restaura la posición. El botón «Mover con teclado» ofrece la misma acción.

Pruebas manuales pendientes antes de E3:

1. Iniciar sesión: comprobar una única burbuja en dashboard, proyectos, colaboración y editor; ninguna en login/registro.
2. Abrir el catálogo, consultar una ficha, regresar y cerrar con Escape; comprobar que el foco vuelve al robot.
3. Arrastrar con ratón y pantalla táctil hasta los cuatro bordes; soltar no debe abrir/cerrar el panel.
4. Mover con F2 y flechas, confirmar con Intro y cancelar con Escape. Probar el botón de movimiento y restablecer posición.
5. Recargar y navegar entre rutas: conservar posición. Reducir la ventana y probar móvil/teclado virtual: mantener controles alcanzables.
6. Abrir un modal existente y comprobar prioridad de foco; comprobar que teclado y arrastre del tutor no activan herramientas del editor.

Validación automatizada: `npm.cmd run test:tutor` incluye catálogo, geometría y preferencias; no sustituye estas pruebas de interacción real. Documento de seguimiento: `PLAN-TUTOR-LOGICDRAFT.md` en la raíz.
