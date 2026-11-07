# Plan del tutor de LogicDraft

Fecha de creación: 6 de noviembre de 2026. Actualización: 7 de noviembre de 2026.

Estado: E1 implementada y validada técnicamente; pendiente de revisión del usuario. El resto del documento describe funciones futuras. No avanzar a otra etapa ni crear commits sin indicación del usuario.

## Seguimiento rápido

**Propósito:** que una persona que nunca utilizó LogicDraft pueda crear y guardar un diagrama sencillo siguiendo al instructor, sin ayuda externa. El instructor enseña a usar la plataforma mediante tutoriales predefinidos; no modifica el diagrama por el usuario ni funciona como chat general.

**Dónde vamos:** catálogo de once tutoriales, contratos, inventario de controles, recorrido de bienvenida y validadores implementados en `Frontend/src/features/tutor/`. Todavía no hay interfaz ni motor ejecutable del tutor; la aplicación conserva su apariencia y comportamiento.

**Próximo paso:** revisión y pruebas del usuario sobre E1. Después de su indicación, E2 (burbuja y panel). Luego se validará el recorrido en texto antes de añadir voz.

| Etapa | Estado | Evidencia de cierre |
| --- | --- | --- |
| Planificación y propósito | Documentados | Este documento y los acuerdos de alcance |
| E1. Catálogo y contratos | Completada técnicamente; revisión del usuario pendiente | 9 pruebas del catálogo, 159 de regresión, build y lint aprobados |
| E2. Burbuja y panel | Pendiente | — |
| E3. Motor de tutoriales | Pendiente | — |
| E4. Bienvenida y voz | Pendiente | — |
| E5. Ayuda contextual escrita | Pendiente | — |
| E6. Peticiones compuestas y clasificador opcional | Pendiente | — |
| E7. Validación integral | Pendiente | — |

Actualizar esta tabla al terminar cada etapa con fecha, archivos principales, comprobaciones ejecutadas y limitaciones. Estados permitidos: pendiente, en curso, bloqueada y completada. Registrar bloqueos funcionales y ambientales por separado; no marcar completada una etapa por haber escrito código sin validarlo.

### Reglas de implementación del usuario

- Crear un módulo separado en frontend, backend y ai-service según lo requiera cada etapa. No crear módulos vacíos donde aún no son necesarios.
- Conservar funcionalidades existentes del proyecto.
- Después de cada tarea, el usuario hará pruebas y decidirá si pide reparaciones, continúa o crea commits. No hacer commits/push ni avanzar de etapa automáticamente.
- Reutilizar componentes apropiados del frontend. Los componentes nuevos específicos irán dentro de `features/tutor/components/` cuando sean necesarios.
- Respetar el diseño y los colores existentes. No introducir una identidad visual distinta para el tutor.
- Estas reglas pueden ampliarse. Registrar aquí los nuevos acuerdos.

## 1. Objetivo y decisiones

Crear un tutor que enseñe a utilizar LogicDraft mediante instrucciones visibles y habladas, resaltado de controles y comprobación de acciones reales. Su entrada será una burbuja con un robot, arrastrable dentro del área visible de la plataforma. Al pulsarla se desplegará el panel del tutor.

Se distinguen dos objetivos:

1. **Actual: enseñar a usar LogicDraft.** Orientar en pantallas, herramientas, creación y guardado de diagramas y, en lecciones posteriores, colaboración, IA e importación/exportación.
2. **Futuro: enseñar modelado.** Explicar entidades, atributos, claves, relaciones y cardinalidades, con ejercicios y evaluación del modelo.

La primera versión tendrá dos entradas al mismo motor: bienvenida después de crear una cuenta y ayuda bajo demanda mediante una petición escrita en la burbuja. Ambas seleccionan y encadenan tutoriales previamente escritos, narrados y comprobables. Compartirán infraestructura con futuras lecciones de modelado, pero no implementarán evaluación conceptual ni conversación libre.

Decisiones iniciales:

- Mantener React, TypeScript y la organización por features del producto existente.
- Implementar la burbuja y el tutor como una feature independiente del agente que modifica diagramas.
- Usar voz del navegador mediante `speechSynthesis` como primera opción.
- Comprobar pasos mediante estado y eventos de la aplicación; la IA no decide si una acción ocurrió.
- Mantener el tutorial básico operativo sin Ollama, Gemini ni Groq.
- Interpretar peticiones contextuales para seleccionar tutoriales de un catálogo cerrado. No generar tutoriales, pasos, explicaciones libres ni selectores de interfaz durante la ejecución.
- Resolver primero mediante búsqueda y reglas locales. Prever un clasificador de IA acotado para lenguaje no reconocido, sin convertirlo en requisito del recorrido básico ni introducir dependencias sin alcance explícito.
- Atender todos los objetivos de peticiones compuestas mediante una cola de tutoriales. No reducir silenciosamente una solicitud a su primera intención.
- Narrar sólo instrucciones, pistas y mensajes de transición predefinidos. El tutor no es un chat de uso general.
- Conservar texto visible y controles manuales aunque la voz no esté disponible.

## 2. Alternativas consideradas

| Experiencia | Requisitos | Decisión |
| --- | --- | --- |
| Recorrido narrado con avance manual | Contenido, resaltado y voz | Útil como modo de exploración; no será la única experiencia |
| Bienvenida práctica narrada | Motor de lecciones, contexto, eventos y estado de bienvenida | Primera versión elegida |
| Ayuda contextual bajo demanda | Interpretación de petición, catálogo cerrado y cola de tutoriales | Segunda entrada de la primera versión |
| Chat abierto que inventa explicaciones o tutoriales | Generación libre | Excluido del diseño |
| Conversación por micrófono | Transcripción, gestión de turnos y coordinación de audio | Ampliación posterior |
| Demostración automática en proyecto de práctica | Entorno de práctica y acciones explícitamente autorizadas | Fuera del primer incremento |

La narración no requiere reconocimiento de voz. Groq se utiliza actualmente para transcribir; no es necesario para que el tutor hable. Si se utiliza Ollama o Gemini, su función educativa en este diseño se limita a identificar objetivos y devolver IDs permitidos del catálogo, no a redactar la respuesta hablada.

### Modo A: bienvenida de una cuenta nueva

- Después del registro exitoso y de entrar en la zona autenticada, ofrecer «Aprende a crear tu primer diagrama», con «Comenzar» y «Ahora no».
- No iniciar audio antes de que el usuario acepte. La burbuja permanece disponible si pospone o abandona el recorrido.
- Guiar por los pasos básicos reales: localizar el workspace, crear/abrir un proyecto, crear clases, nombrarlas, añadir atributos y claves, conectar clases, ajustar cardinalidad y verificar guardado.
- Usar el mismo catálogo y motor del modo bajo demanda; no duplicar una implementación especial para el registro.
- Distinguir cuenta recién creada de primer acceso en un navegador. No inferir cuenta nueva por ausencia de `localStorage`.
- Primera entrega: señal explícita del registro exitoso, progreso de sesión y preferencia local de bienvenida por usuario y versión de catálogo. El marcador local evita repetición en ese navegador, pero no sincroniza dispositivos ni prueba la antigüedad de la cuenta.
- Una nueva instalación del navegador o pérdida del marcador no autoriza presentar una cuenta antigua como nueva. Siempre ofrecer reiniciar manualmente el tutorial. Persistencia de bienvenida por cuenta en servidor queda como ampliación explícita si se requiere esa garantía entre dispositivos.

### Modo B: petición de ayuda desde cualquier pantalla

Ejemplo: «¿Cómo creo una clase?».

1. Recibir la petición completa y obtener la pantalla y el contexto de la aplicación.
2. Reconocer todos sus objetivos y buscar tutoriales existentes.
3. Resolver prerrequisitos según la ubicación: desde dashboard, guiar al workspace/proyecto; desde proyectos, abrir el editor; desde el editor, ir directamente al control adecuado.
4. Mostrar la ruta propuesta y comenzar mediante una acción del usuario.
5. Resaltar el botón o campo actual, narrar su paso y esperar evidencia de cumplimiento.
6. Continuar con el siguiente paso/tutorial hasta cubrir la petición o identificar un bloqueo.

Si hay varios workspaces o proyectos posibles, pedir que el usuario elija. Una ruta puede estar en la pantalla de proyectos de un workspace sin que exista una página independiente llamada «Workspace»: el contexto se toma del router y de los datos reales.

## 3. Burbuja de robot y panel

### Burbuja global

- Mostrarla en las pantallas autenticadas: dashboard, proyectos, colaboración y editor.
- Posición inicial propuesta: esquina inferior derecha, con margen respecto de controles existentes.
- Permitir arrastrarla con ratón, lápiz o pantalla táctil mediante Pointer Events y captura del puntero.
- Distinguir clic de arrastre mediante un umbral de desplazamiento; soltar después de arrastrar no abre el panel.
- Limitar la posición al viewport; recalcularla cuando cambien el tamaño de pantalla, orientación o zoom relevante.
- Guardar únicamente la preferencia de posición local, con un formato versionado. Si la posición deja de ser válida, ajustarla o restaurarla.
- Permitir movimiento por teclado en un modo explícito y ofrecer «Restablecer posición».
- Utilizar un icono de robot SVG o componente compatible con el sistema visual existente; no hace falta generar una imagen ni instalar una biblioteca de mascotas.
- Mantener una única instancia al navegar entre rutas autenticadas. Al cerrar sesión, detener voz y limpiar contexto y progreso temporal.

### Panel desplegable

Al hacer clic en el robot, abrir un panel asociado a su posición. Elegir el lado con espacio disponible y limitar tamaño y posición al viewport. En móvil puede convertirse en una hoja inferior, conservando la burbuja como acceso.

Contenido previsto:

- Título del tutor, campo «¿Qué quieres aprender a hacer?» y selector de tutoriales disponibles.
- Tarjeta de bienvenida cuando corresponda y una cola visible con todos los objetivos identificados, tutoriales y prerrequisitos.
- Objetivo del paso, explicación breve y progreso, por ejemplo «Paso 2 de 5».
- Botones «Iniciar», «Mostrar dónde», «Repetir», «Silenciar», «Anterior», «Omitir» y «Salir», según el estado.
- Pistas escritas predefinidas, controles para aclarar peticiones ambiguas y mensajes cerrados para solicitudes no cubiertas.
- Mensajes claros cuando falte permiso, contexto, un control o confirmación de guardado.
- Estado final de completado y posibilidad de reiniciar la lección.

Cerrar el panel conserva el avance de la sesión, pero pausa el tutorial y detiene la narración. Volver a abrirlo no reproduce automáticamente; el usuario elige continuar. Cambiar de lección cancela el audio anterior y pide una acción explícita antes de iniciar la nueva.

Una nueva petición durante un recorrido ofrece «Añadir al recorrido» o «Reemplazar recorrido». Nunca descarta silenciosamente tareas pendientes. «Anterior» permite revisar una explicación sin deshacer operaciones de negocio. «Omitir» no ejecuta la acción ni satisface un prerrequisito necesario.

### Accesibilidad y convivencia

- Burbuja implementada como botón con nombre accesible y estado expandido.
- Acceso mediante teclado, foco visible, Escape para cerrar y devolución del foco al robot.
- Panel no modal en escritorio para permitir trabajar en la aplicación. Si en móvil es modal, gestionar el foco como tal.
- No depender del color, del audio ni del arrastre para transmitir instrucciones.
- Respetar reducción de movimiento. No anunciar cada actualización mediante lectores de pantalla ni duplicar narración innecesariamente.
- Definir capas visuales: el tutor no debe tapar modales críticos, diálogos de propuestas o avisos de conflicto.
- El arrastre del robot no debe mover el lienzo React Flow, crear relaciones ni provocar desplazamientos accidentales de la página.
- El panel debe poder reubicarse o cerrarse para no ocultar el control que explica.

## 4. Primer tutorial: crear y guardar un diagrama

El contenido exacto y los identificadores de controles se confirmarán contra la interfaz actual antes de implementar. No se basarán exclusivamente en documentos antiguos.

| Paso | Instrucción propuesta | Evidencia de cumplimiento |
| --- | --- | --- |
| Preparación | Elegir un workspace donde pueda editar y comenzar la lección | Contexto y permisos disponibles |
| 1 | Crear un proyecto y abrir su editor | Creación confirmada por API y navegación al proyecto correspondiente |
| 2 | Crear una clase usando el control correspondiente del editor | Acción local y nodo del tipo esperado presente en el proyecto |
| 3 | Cambiar su nombre a Cliente | Nodo identificado por ID con el nombre esperado |
| 4 | Agregar id y nombre, elegir tipos y configurar la clave primaria donde la UI lo permita | Atributos y configuración presentes en el nodo del ejercicio |
| 5 | Crear una segunda clase Pedido | Segundo nodo creado por el usuario y correctamente identificado |
| 6 | Conectar Cliente y Pedido | Relación entre los IDs del ejercicio |
| 7 | Configurar la cardinalidad en el panel correspondiente | Extremos y cardinalidades coinciden con el paso |
| 8 | Comprobar el guardado | Respuesta de guardado exitosa para la revisión que contiene los cambios del ejercicio |

El guardado automático existente debe explicarse; no se inventará un botón de guardado si no corresponde a la interfaz vigente. Un evento colaborativo de guardado de otro usuario no demuestra que se guardaron los cambios propios.

Condiciones especiales:

- Si el usuario parte de un proyecto existente, ofrecer una ruta explícita para comenzar desde el editor; no crear ni eliminar datos automáticamente.
- No dar por realizado un ejercicio porque exista previamente una entidad con el mismo nombre. Vincular los pasos a IDs y al contexto de la lección.
- «Omitir» se registra como omitido, no como completado.
- Para VIEWER, ofrecer explicación o recorrido de lectura; nunca inducir modificaciones ni eludir permisos.
- Si un colaborador completa una condición, distinguirlo de una acción del alumno. Ofrecer continuar desde el estado observado sin atribuirle esa acción.
- Si el usuario cambia de proyecto o elimina un elemento necesario, pausar y explicar cómo recuperar el contexto.
- Los errores 409 o 422 mantienen pendiente el paso de guardado; no se ocultan ni se consideran éxito.

Este recorrido se compone de tutoriales pequeños reutilizables: abrir editor, crear clase, renombrar, añadir atributo, configurar clave, crear relación, configurar cardinalidad y verificar guardado. Cada uno también puede seleccionarse mediante una pregunta. El catálogo debe distinguir clase UML y entidad de datos cuando sus controles o requisitos difieran; no reinterpretar una petición de clase como entidad silenciosamente.

Lecciones posteriores: colaboración, solicitudes a la IA existente, revisión de propuestas, importación/exportación y generación de aplicaciones. Hasta publicarlas, esas funciones pueden existir en el producto sin tener todavía un tutorial disponible.

## 5. Voz

### Primera implementación: navegador

Crear un adaptador sobre `speechSynthesis` y `SpeechSynthesisUtterance`:

- Detectar soporte y cargar voces con `getVoices()` y `voiceschanged`.
- Preferir una voz española disponible; permitir selección cuando haya varias.
- Si no existe una voz adecuada o falla la reproducción, mantener el tutorial en texto y explicar la limitación.
- Iniciar audio mediante una interacción explícita del usuario.
- Narrar instrucciones breves y preescritas por paso; no leer código, IDs internos o mensajes técnicos. Sólo interpolar parámetros permitidos, escapados y comprobados, como el nombre del proyecto elegido.
- Esperar a que el objetivo esté montado y resaltado antes de narrar. Terminar el audio no completa el paso: se espera la condición real o un botón de continuación en pasos exclusivamente informativos.
- Cancelar la cola anterior al cambiar de paso, salir, cerrar sesión o desmontar el tutor.
- Evitar repeticiones provocadas por renderizados de React y respuestas tardías.
- Coordinar la narración con el micrófono existente: detenerla antes de grabar para evitar transcribir la voz del tutor.
- No garantizar operación sin Internet: depende de la voz y del navegador. El texto sí permanece disponible.

La primera versión no requiere claves nuevas ni permisos de micrófono. La disponibilidad y calidad de voz deben comprobarse en los navegadores y equipos de la demostración.

### Alternativas posteriores

| Alternativa | Necesidades e implicaciones |
| --- | --- |
| Audio preparado | Archivos de audio por lección, reproducción y actualización al cambiar el texto; voz consistente, pero sin respuestas dinámicas |
| Gemini TTS u otro servicio remoto | Modelo de audio compatible, permisos/cuota, endpoint autenticado, credenciales del lado servidor, gestión de latencia y fallos |
| TTS local dedicado | Motor y voces compatibles con español, instalación, recursos y validación de licencia; supone un servicio adicional |

La elección de TTS remoto se evaluará con una prueba de voz en español, tiempo de respuesta y coste. La clave del proveedor de texto no garantiza acceso al modelo de audio.

## 6. Arquitectura y contratos propuestos

### Separación de responsabilidades

```text
Registro exitoso -> Oferta de bienvenida ----+
                                            |
Petición escrita -> Selector de catálogo ----+--> Planificador de cola
                      ^                              ^
                      |                              |
        Catálogo cerrado de tutoriales     Contexto actual y prerrequisitos
                                                     |
                                                     v
                                  Motor determinista + eventos de UI
                                                     |
                                                     v
                                  Panel + resaltado + voz predefinida

Clasificador opcional: React -> Spring autorizado -> FastAPI -> Gemini / Ollama
Salida del clasificador: intenciones e IDs del catálogo; nunca pasos generados
```

El tutor observa acciones y orienta. Las acciones de negocio siguen utilizando los servicios, permisos y validaciones actuales. El primer incremento no necesita endpoints nuevos ni cambios en PostgreSQL.

### Contratos orientativos

Los nombres siguientes son propuestas, no tipos existentes:

- `TutorialDefinition`: `id`, `contentVersion`, `domain` (`platform` o `modeling`), título, requisitos y pasos.
- Ampliar la definición con intenciones, sinónimos, pantallas de entrada, prerrequisitos, resultados esperados y capacidades soportadas. Versionar también el catálogo completo.
- `TutorialStep`: `id`, texto visible, texto hablado opcional, `targetId`, pistas, requisito y condición de cumplimiento identificada.
- `TutorialContext`: ruta, ámbito de usuario/proyecto, permisos observados, selección y estado mínimo necesario para el ejercicio.
- `TutorialEvent`: identificador de evento, origen local/remoto, ámbito y tipo semántico; no eventos por píxel.
- `TutorialProgress`: lección, versión de contenido, ámbito, paso actual y pasos completados u omitidos.
- `TutorialRequestResolution`: estado (`MATCHED`, `NEEDS_CLARIFICATION`, `PARTIALLY_SUPPORTED`, `UNSUPPORTED`, `OUT_OF_SCOPE`), objetivos y fragmentos de la petición que cubre cada uno, IDs de tutoriales y códigos de aclaración. Los mensajes visibles se resuelven desde plantillas locales.
- `TutorialQueue`: secuencia de instancias de tutorial con parámetros validados, objetivos originales cubiertos, prerrequisitos y estado. Permitir repetir un tutorial con destinos distintos; no confundir dos clases solicitadas con un duplicado.
- `OnboardingState`: señal de registro, ofrecido/pospuesto/iniciado/completado, versión y ámbito de usuario. Separar de la revisión del documento de diagrama.
- `SpeechAdapter`: disponibilidad, lectura de texto, cancelación, estado y notificación de errores.
- `TutorialTargetRegistry`: asociación entre un ID estable permitido y una referencia al control correspondiente.

Las condiciones se implementan como funciones registradas y comprobables. El catálogo no contendrá JavaScript ejecutable ni expresiones evaluadas con `eval`.

Estados mínimos del motor: inactivo, preparado, activo, esperando acción, pausado y completado. Los estados de voz se gestionan aparte para que un fallo de audio no bloquee el aprendizaje.

### Integración con el producto

- Introducir un layout autenticado bajo `ProtectedRoute` que contenga el `Outlet` y el host global del tutor. `App.tsx` actualmente monta `RouterProvider`; no colocar allí un consumidor de navegación fuera del router.
- Publicar contexto mínimo desde las páginas mediante hooks/adaptadores, evitando aumentar la lógica del editor principal.
- Añadir referencias o atributos estables de ayuda a controles concretos; no localizar botones por su texto traducible o por selectores CSS frágiles.
- El resaltado recalcula posición al cambiar tamaño, scroll o montaje del objetivo. Si no existe, mostrar una alternativa en lugar de bloquear la pantalla.
- Consumir acciones semánticas existentes y deduplicar su observación. No publicar eventos STOMP por mover el robot o avanzar en la lección.
- Mantener el progreso detallado y la cola en memoria durante la sesión. Guardar localmente preferencias de presentación y el marcador de bienvenida por usuario y versión; nunca contenido del diagrama, peticiones ni conversaciones. Persistencia de progreso entre sesiones/dispositivos queda para otra etapa.
- Reevaluar contexto al navegar o cambiar permisos, insertar únicamente prerrequisitos registrados y pausar si falta una ruta conocida. Nunca fabricar pasos para superar un bloqueo.

## 7. Posibles carpetas y archivos afectados

Las rutas nuevas son propuestas. No se crearán todas en la primera subetapa.

### Frontend: primera versión

```text
Frontend/src/features/tutor/                 # Nueva feature
  components/
    TutorBubble.tsx
    TutorPanel.tsx
    TutorHighlight.tsx
    TutorHost.tsx
  context/
    TutorProvider.tsx
  hooks/
    useTutor.ts
    useDraggableBubble.ts
    useTutorialTarget.ts
  services/
    tutorialEngine.ts
    tutorialConditions.ts
    tutorialRequestResolver.ts
    tutorialQueuePlanner.ts
    tutorialTargetRegistry.ts
    browserSpeechAdapter.ts
    tutorPreferences.ts
    onboardingState.ts
  content/
    catalog.ts
    tutorMessages.ts
    platform/firstDiagram.ts                 # Recorrido compuesto
    platform/openEditor.ts
    platform/createClass.ts
    platform/addAttribute.ts
    platform/createRelationship.ts
    platform/configureCardinality.ts
    platform/verifySave.ts
  types/
    tutor.types.ts
  tutor.css

Frontend/src/app/AuthenticatedLayout.tsx     # Nuevo layout propuesto
Frontend/src/app/router.tsx                  # Integración bajo protección existente
Frontend/src/shared/components/AppIcon.tsx   # Icono robot si encaja en el sistema
Frontend/tests/                              # Pruebas del motor, voz e interacción
```

Puntos existentes que podrían requerir instrumentación limitada:

| Ruta | Motivo |
| --- | --- |
| `Frontend/src/features/dashboard/` | Ayuda inicial y contexto de workspace |
| `Frontend/src/features/project/` | Crear proyecto y abrir editor |
| `Frontend/src/features/diagram/components/` | Referencias de controles y resaltado |
| `Frontend/src/features/diagram/pages/DiagramEditorPage.tsx` | Conectar contexto, acciones y confirmación de guardado mediante adaptador |
| `Frontend/src/features/diagram/services/` | Coordinar con grabación de voz si no existe una señal reutilizable |
| `Frontend/src/features/auth/` | Señal de registro exitoso, ámbito de bienvenida y limpieza de sesión |
| `Frontend/package.json` | Registrar pruebas nuevas en scripts; sin dependencias nuevas previstas |

`features/agent/` conserva la responsabilidad de su agente actual. No debe mezclarse su historial ni su capacidad de proponer modificaciones con las consultas educativas.

### Backend: sólo para ampliaciones autorizadas

- Posible dominio `backend/src/main/java/com/sw1/backend/tutor/`, con controller, DTO y service para clasificación acotada de solicitudes o progreso persistente.
- Reutilizar servicios de acceso de `auth/`, `tenant/`, `workspace/` y `proyecto/`.
- Evaluar reutilización de `ai/client/` para comunicación con FastAPI sin duplicar infraestructura.
- Pruebas bajo `backend/src/test/java/com/sw1/backend/tutor/`.
- Una migración futura en `backend/src/main/resources/db/migration/` sólo si se decide persistir progreso. No alterar bases existentes sin autorización y respaldo verificado.

### FastAPI: sólo si se incorpora el clasificador opcional o voz remota

```text
ai-service/app/api/routes/tutor.py
ai-service/app/schemas/tutor.py
ai-service/app/services/tutor_service.py
ai-service/app/skills/tutor/
ai-service/app/main.py                       # Registro de la nueva ruta
ai-service/tests/tutor/
```

Reutilizar `services/providers/` para clasificación estructurada, con un contrato pequeño y catálogo de IDs permitido, independiente de las operaciones de diagrama. Nunca devolver instrucciones educativas inventadas. Un proveedor de voz futuro tendrá su propio contrato, separado del proveedor de texto. No añadir servicios de voz en esta primera versión.

### Documentación e infraestructura

- Actualizar `DEVELOPMENT.md` y documentación de frontend cuando exista implementación verificable.
- Revisar contradicciones de `ARCHITECTURE.md` en una tarea de documentación explícita.
- No se prevén cambios iniciales en Docker, `compose.deploy.yml`, generadores, XMI, contratos persistidos de diagrama ni AWS.

## 8. Selección de tutoriales, peticiones compuestas y límites

### Selección con bajo consumo

1. Buscar localmente usando intenciones, ejemplos de peticiones y sinónimos curados del catálogo. Considerar la frase completa, negaciones, orden, pronombres y todos sus objetivos; no basta con detectar una palabra como «clase».
2. Si hay una coincidencia inequívoca, construir la cola mediante reglas deterministas.
3. Ante ambigüedad, mostrar opciones breves predefinidas. Si se incorpora IA para interpretar lenguaje más variado, limitarla a clasificar una petición explícita y devolver intenciones/IDs y parámetros permitidos.
4. Validar siempre los IDs, versión de catálogo, tipos de parámetros y cobertura de la petición. Una respuesta del modelo no autoriza crear un tutorial ni ejecutar acciones.
5. Ejecutar los pasos desde el catálogo sin volver a consultar al modelo por clic, navegación, resaltado o reproducción de voz.

El clasificador opcional tendrá un máximo inicial propuesto de una llamada por petición enviada, contexto mínimo, límite de salida, timeout y ninguna cadena de reintentos automáticos. Enviar sólo las descripciones e IDs necesarios para seleccionar; no el catálogo completo de pasos ni el grafo del proyecto. Su salida no se mostrará ni se narrará directamente.

Si el proveedor está apagado o no se resuelve la petición, ofrecer el catálogo y una aclaración. Los recorridos conocidos siguen funcionando. No se necesita entrenamiento, base vectorial, generación libre ni almacenamiento del historial de chat.

### Encadenamiento sin perder objetivos

Ejemplo: «Enséñame a crear dos clases, agregarles atributos y relacionarlas».

La resolución debe conservar los tres objetivos y la cantidad solicitada. La cola reutiliza crear clase dos veces, añadir atributos a los destinos elegidos y relacionar esos mismos nodos. Si está en dashboard, antepone los tutoriales de acceso al editor. Si ya está en el editor, no repite esa navegación.

- Mostrar un resumen de la cobertura antes de comenzar: objetivos atendidos, prerrequisitos y dudas pendientes.
- Mantener el orden solicitado siempre que respete dependencias; explicar mediante plantilla cuando un prerrequisito deba ejecutarse primero.
- Preguntar qué clases o atributos usar cuando falten destinos o existan varios candidatos; no inventarlos. Los ejemplos fijos de bienvenida están identificados como ejercicios.
- Deduplicar sólo prerrequisitos equivalentes; conservar acciones repetidas con distinta cantidad o destino.
- Detectar ciclos en las dependencias del catálogo y bloquear rutas inválidas antes de publicarlas.
- Distinguir prerrequisito ya satisfecho de ejercicio realizado por el usuario. Llegar con un editor abierto permite omitir navegación, pero no atribuirle la creación previa de un nodo.
- Si una petición es demasiado extensa para procesarse con confianza, explicarlo y pedir dividirla; nunca truncarla silenciosamente.
- Una petición parcialmente soportada no se anuncia como completamente resuelta. Mostrar qué falta y ofrecer comenzar la parte disponible mediante elección explícita.

### Respuestas acotadas y honestas

Todos estos mensajes serán plantillas del catálogo:

| Situación | Respuesta orientativa |
| --- | --- |
| Fuera del propósito, por ejemplo una receta | «Puedo enseñarte a usar LogicDraft. Esa solicitud está fuera de los tutoriales de uso de la plataforma.» |
| Función de LogicDraft sin tutorial disponible | «Eso pertenece a LogicDraft, pero todavía no tengo un tutorial para esa función.» |
| Petición ambigua | «¿Quieres crear una clase o agregar un atributo a una clase existente?» |
| Parte soportada y parte no cubierta | «Puedo guiarte para crear la clase. Todavía no tengo un tutorial para la otra acción. ¿Quieres comenzar por la parte disponible?» |
| Falta de permisos | «Tu rol permite consultar este proyecto. Para practicar cambios, elige un proyecto donde puedas editar.» |
| Pregunta conceptual de modelado | «Por ahora enseño a utilizar las herramientas de LogicDraft. Las lecciones de modelado aún no están disponibles.» |

No calificar como ajeno a la plataforma algo que sólo falta en el catálogo. Si el tutor no entiende una frase, solicitar aclaración antes de declararla fuera de alcance. No responder conversaciones generales, improvisar soluciones ni atribuir capacidades inexistentes.

### Expansión futura a modelado

La futura enseñanza de modelado reutilizará catálogo, selector, motor, panel, voz y contexto, incorporando contenidos revisados del dominio `modeling`, ejercicios y evaluadores propios. Mantendrá la política de tutoriales predefinidos y separados de las reglas de integridad del producto. La corrección conceptual no debe confundirse con la validez estructural del documento.

## 9. Seguridad, compatibilidad y datos

- Consultar o pedir una explicación no autoriza cambios en diagramas.
- El tutor inicial no llama directamente a operaciones de creación o eliminación; el usuario actúa mediante la UI existente.
- Los permisos observados por React sólo orientan la experiencia; Spring sigue siendo la barrera de seguridad.
- Respetar los formatos de diagrama ya soportados por el código (`version: 1 | 2`) y la compatibilidad con documentos v1; no migrarlos como efecto del tutorial. `revision` y `baseRevision` son controles de concurrencia, no versiones de lecciones. El progreso tutorial nunca se almacena dentro del diagrama.
- Conservar comportamiento 409 ante revisiones obsoletas y 422 ante documentos inválidos.
- No modificar el documento para registrar pasos, ni los eventos colaborativos para sincronizar progreso personal.
- No leer ni mostrar secretos; las credenciales de servicios remotos permanecen en el servidor.
- No grabar audio en la primera versión. El micrófono conversacional futuro requerirá activación explícita y permisos del navegador.
- No enviar el diagrama completo a un servicio de voz; sólo el texto necesario para narrar.
- No introducir analítica ni almacenamiento de conversaciones como efecto secundario.

## 10. Etapas de implementación y cierre

La secuencia E1–E7 reemplaza la división anterior T1–T5 de este documento. Pertenece al tutor y no sustituye ni renumera las fases generales del proyecto. Las pruebas se ejecutan durante cada etapa; E7 integra los resultados, no pospone toda la validación hasta el final.

### E1. Catálogo y contratos

**Trabajo:** revisar los controles reales y definir tutoriales, pasos, prerrequisitos, destinos, textos, condiciones de cumplimiento y contratos de contexto/progreso. Separar clase UML de entidad de datos cuando la UI lo requiera.

**Herramientas:** TypeScript, catálogo versionado y revisión manual de la interfaz. Sin modelo de lenguaje ni servicios nuevos.

**Catálogo inicial:** elegir workspace, crear proyecto, abrir editor, crear clase, renombrar, añadir atributo/tipo, configurar clave donde corresponda, crear segunda clase, relacionar clases, ajustar cardinalidad y comprobar guardado. La bienvenida compone esos módulos; la ayuda reutiliza sólo los necesarios.

**Áreas:** `features/tutor/types/`, `features/tutor/content/` y referencias a controles existentes.

**Cierre:** IDs únicos, dependencias sin ciclos y cada paso asociado a una capacidad real y una condición comprobable. Registrar limitaciones de permisos y variantes v1/v2.

### E2. Burbuja y panel

**Trabajo:** integrar el host en rutas autenticadas, robot arrastrable, límites de pantalla, teclado, apertura/cierre, estado visual y preferencias de posición.

**Herramientas:** React, CSS, Pointer Events, React Router e icono SVG del sistema existente. Sin LLM ni nuevas bibliotecas previstas.

**Áreas:** `features/tutor/components/`, hooks y estilos; layout autenticado y router.

**Cierre:** pruebas de clic frente a arrastre y límites, navegación/logout, revisión móvil/teclado y ausencia de interferencia con React Flow. El panel todavía no se presenta como tutorial funcional.

### E3. Motor de tutoriales

**Trabajo:** ejecutar el catálogo en texto, resaltar objetivos, esperar acciones, avanzar, pausar, repetir y gestionar rutas, prerrequisitos y contexto invalidado.

**Herramientas:** estado/contexto de React, funciones TypeScript comprobables, React Router y adaptadores a eventos semánticos actuales. Sin LLM.

**Áreas:** motor, condiciones y registro de objetivos en `features/tutor/services/`; instrumentación limitada de dashboard, proyectos y editor.

**Cierre:** recorrido completo desde proyecto hasta guardado en texto. Probar acciones locales/remotas, reinicio, omisión, cambio de proyecto, VIEWER y fallos 409/422. Confirmar que no hay escrituras de negocio efectuadas por el tutor.

### E4. Bienvenida y voz

**Trabajo:** ofrecer recorrido tras registro exitoso, permitir posponer/reiniciar y narrar los pasos. Implementar selección de voz, repetición, silencio, cancelación y coordinación con el micrófono existente.

**Herramientas:** señal de registro de la aplicación, preferencias locales por usuario y Web Speech API (`speechSynthesis`). No requiere API de voz, descarga de modelo ni permisos de micrófono.

**Áreas:** integración de `features/auth/`, `onboardingState.ts`, `browserSpeechAdapter.ts` y panel del tutor.

**Cierre:** cuenta nueva completa el recorrido con voz o sólo texto. Probar cuentas existentes, cambio de usuario y pérdida del marcador local. Comprobar audio real en navegador objetivo y que el final de la narración no marque una acción como realizada.

### E5. Ayuda contextual escrita

**Trabajo:** reconocer preguntas frecuentes, negaciones y ambigüedad; seleccionar tutoriales según pantalla, selección y permisos. Ofrecer catálogo y aclaraciones cuando no haya coincidencia segura.

**Herramientas:** catálogo de intenciones/sinónimos y reglas locales. Inicialmente sin LLM; no usar embeddings ni base vectorial.

**Áreas:** `tutorialRequestResolver.ts`, contenido de mensajes y contexto publicado por páginas.

**Cierre:** «¿Cómo creo una clase?» conduce a rutas adecuadas desde dashboard, proyectos y editor. Peticiones no cubiertas y ajenas al producto reciben mensajes diferentes. La ayuda escrita es parte de la versión acordada, no un chat general posterior.

### E6. Peticiones compuestas e interpretación opcional con IA

**Trabajo:** identificar todos los objetivos, cantidades y destinos; encadenar tutoriales y prerrequisitos sin omisiones. Mostrar dudas y cobertura parcial antes de comenzar. Medir qué peticiones no resuelve el selector local.

**Herramientas obligatorias:** planificador determinista de colas y contratos TypeScript. **Herramientas opcionales:** Spring como proxy autorizado, FastAPI y proveedor Ollama o Gemini para clasificación estructurada limitada.

**Áreas:** `tutorialQueuePlanner.ts`, resolutor y pruebas. Sólo si se decide incorporar el clasificador: dominio `tutor/` de Spring, ruta/schema/service `tutor` de FastAPI e integración del cliente.

**Cierre:** peticiones compuestas conservan todos los objetivos y cantidades; no confunden tutoriales repetidos sobre destinos distintos. Probar catálogos incompatibles, IDs inválidos, timeouts y proveedor apagado. Confirmar cero llamadas al LLM al avanzar pasos. La elección del modelo requiere comparar resultados; puede cerrarse la etapa sin LLM si la solución local cubre los casos acordados, documentando el alcance.

### E7. Validación integral

**Trabajo:** recorrer ambos modos de extremo a extremo y evaluar permisos, colaboración, navegación, persistencia, voz, accesibilidad y recuperación de fallos.

**Herramientas:** scripts de pruebas existentes, pruebas nuevas pertinentes, comprobaciones manuales en navegadores objetivo y sesiones con personas que no conocen LogicDraft. No requiere LLM adicional.

**Cierre principal:** una persona nueva crea, relaciona y guarda un diagrama sencillo sin ayuda externa. Registrar pasos completados, bloqueos, indicaciones confusas y si el tutor informa honestamente lo que falta. No declarar funciones terminadas sólo porque el modelo contestó o el panel se ve correctamente.

### Fuera de estas siete etapas

Gemini TTS, preguntas por micrófono, progreso persistente en servidor y lecciones de modelado se planifican por separado. El chat general y la generación de tutoriales en tiempo real permanecen fuera del diseño.

### Herramientas y elección de modelo

| Recurso | Uso | Cuándo se necesita |
| --- | --- | --- |
| React + TypeScript + CSS + React Router | Interfaz, motor, catálogo y navegación | E1–E7 |
| Pointer Events y SVG | Robot arrastrable y accesible | E2 |
| Web Speech API | Narración de textos del catálogo | E4 |
| Reglas locales | Selección de tutoriales conocidos y planificación | E5–E6 |
| Llama 3.2 con Ollama | Clasificar peticiones en intenciones conocidas | Opcional en E6, sujeto a calidad y latencia medidas |
| Gemini | Alternativa para la misma clasificación | Opcional en E6, sujeto a calidad, acceso y cuota |
| Spring + FastAPI existentes | Autorización y acceso al clasificador | Sólo si se incorpora clasificación remota |
| Groq | Transcripción de preguntas habladas | Fuera de esta versión |

No hacen falta entrenamiento, nueva base de datos ni API TTS para el alcance inicial. Comprobar una voz española en el equipo de demostración antes de cerrar E4. Mantener las dependencias existentes salvo necesidad explícitamente acordada.

Si se evalúan modelos, usar el mismo conjunto de preguntas conocidas, paráfrasis, solicitudes compuestas, negaciones, ambigüedades y temas fuera de alcance. Comparar IDs válidos, cobertura de objetivos, rechazos correctos, latencia y consumo. No dar por suficiente un modelo porque responde un saludo.

Salida ilustrativa del clasificador (no equivale al contrato completo):

```json
{
  "status": "MATCHED",
  "tutorialIds": ["create_class", "add_attribute"]
}
```

El contrato final incluirá cobertura y parámetros tipados cuando se requieran cantidades o destinos. El programa verifica los IDs y compone los pasos; nunca reproduce texto libre del modelo. No conectar este clasificador al intérprete de la barra inferior: seleccionar lecciones y proponer mutaciones son responsabilidades distintas.

### Presupuesto de llamadas

- Cero llamadas a LLM para bienvenida, selección manual, resaltado, movimiento del robot, narración y avance de pasos.
- Resolver localmente peticiones conocidas.
- Si se incorpora IA, máximo inicial de una llamada por petición enviada; sin reintentos automáticos.
- Enviar sólo contexto mínimo y descripciones de catálogo necesarias, no el diagrama completo ni todas las instrucciones.
- Si falla el proveedor, mantener selector manual y mensajes de aclaración; no bloquear el motor.

### Registro de avance por etapa

Al cerrar cada etapa añadir una entrada con: fecha, etapa, resultado entregado, archivos afectados, comandos y resultados de validación, escenarios manuales, compatibilidad, limitaciones y próximo paso. Una prueba simulada no demuestra calidad del modelo real ni funcionamiento del audio del navegador.

**7 de noviembre de 2026 — Planificación:** propósito, dos modos, catálogo cerrado, siete etapas y recursos documentados. No se implementó el tutor, no se añadió un proveedor y no se ejecutaron pruebas funcionales de una implementación inexistente. Próximo paso: E1.

**7 de noviembre de 2026 — E1 entregada:** módulo `Frontend/src/features/tutor/` con contratos de catálogo/contexto/evidencia/progreso, 21 destinos lógicos documentados, once tutoriales, trece instancias en el recorrido de bienvenida, mensajes cerrados y validación de autoría. Script `npm.cmd run test:tutor` y pruebas en `Frontend/tests/tutorCatalog.test.mjs`. Documentación del contrato y condiciones de integración en el README del módulo.

Validación: 9 pruebas del catálogo y 159 pruebas existentes aprobadas; `npm.cmd run build` y `npm.cmd run lint` correctos. Build conserva la advertencia de bundle mayor a 500 kB. Sin cambios en endpoints, permisos, datos, estilos ni formato persistido; backend/ai-service no requieren implementación en E1. No se ejecutaron pruebas Java/Python porque no se cambiaron esos servicios. Los destinos todavía no están registrados en React y las condiciones son declaraciones, no un motor en funcionamiento. Pendiente de pruebas/revisión del usuario; sin commits. Los cambios previos de `Frontend/package-lock.json` y `backend/src/main/resources/application.properties` se conservaron.

Cada subetapa debe terminar con contrato actualizado, pruebas unitarias relevantes, integración necesaria, revisión de compatibilidad y diff. No avanzar con fallos funcionales. Si hay bloqueo ambiental, registrar comando y error sin declarar aprobada la validación.

## 11. Validación prevista

Desde la raíz, ejecutar en PowerShell las comprobaciones del frontend:

```powershell
cd Frontend
npm.cmd run test:diagram-ai
npm.cmd run test:tutor
npm.cmd run build
npm.cmd run lint
```

Las nuevas pruebas se incorporarán a un script declarado que se ejecute en la validación, sin dejarlas fuera de la suite por accidente. Si se modifican Java o Python en una etapa futura, ejecutar además las suites correspondientes con Maven Wrapper y el entorno virtual:

```powershell
cd ai-service
.\venv\Scripts\Activate.ps1
python -m pytest -q

# Desde la raíz, en otra terminal:
cd backend
.\mvnw.cmd test
```

Escenarios manuales esenciales:

- Arrastrar el robot por dashboard, proyectos y editor sin activar herramientas accidentalmente.
- Navegar entre pantallas conservando una única instancia y posición válida.
- Completar la lección con teclado y también con audio desactivado.
- Probar viewport pequeño, scroll, paneles laterales y modales.
- Cancelar correctamente la voz al cerrar, salir, cambiar de paso o activar grabación.
- No avanzar por cambios de otro usuario atribuyéndolos al alumno.
- Mantener el paso pendiente ante conflicto o fallo de guardado.
- Ejecutar el tutorial sin servicios externos de IA disponibles.
- Ofrecer bienvenida sólo a partir de una señal real de registro, sin confundir cambio de navegador con cuenta nueva.
- Resolver la misma pregunta desde dashboard, workspace/proyectos y editor; resaltar únicamente controles existentes.
- Encadenar varios objetivos y cantidades sin omisiones; mostrar de forma explícita partes no soportadas.
- Esperar acciones reales, no el final del audio ni temporizadores, para marcar pasos prácticos completos.
- Probar desvíos de navegación, cambios de permisos, objetivos desmontados y prerrequisitos omitidos.
- Intentar peticiones fuera de alcance y órdenes para inventar tutoriales: sólo mensajes y IDs del catálogo pueden llegar a la UI.
- Validar dependencias sin ciclos, IDs únicos, controles registrados y versionado del catálogo.

En E1 se ejecutaron pruebas del catálogo, regresión, build y lint según el registro anterior. Los escenarios de interfaz, voz y ejecución del tutorial siguen pendientes de sus etapas correspondientes; no se declaran aprobados por la validación estática.

## 12. Referencias técnicas

- Voz del navegador y carga de voces: https://developer.mozilla.org/en-US/docs/Web/API/SpeechSynthesis/voiceschanged_event
- Generación de voz con Gemini, opción futura: https://ai.google.dev/gemini-api/docs/speech-generation
- Capacidades de TTS de Groq, a revisar antes de elegirlo para narración en español: https://console.groq.com/docs/text-to-speech

Las capacidades, modelos, cuotas y condiciones de proveedores deben verificarse de nuevo cuando se decida integrar voz remota.
