# Tutoriales de LogicDraft: cuándo actuar y cómo comprobar los pasos

**E3.4 en revisión:** ya se pueden iniciar las fichas con IDs y parámetros explícitos; el dashboard ofrece el recorrido completo. Se conectaron acciones locales y guardado confirmado al motor. `runtimeAvailable: true`. Se validaron en Edge con APIs simuladas las guías de creación de clase y guardado; aún falta validar el recorrido completo de trece lecciones antes de cerrar E3. Las notas históricas siguientes describen los estados previos.

Fecha: 7 de noviembre de 2026. Catálogo v1, idioma español.

## Propósito y estado

El instructor enseñará a utilizar LogicDraft: mostrará instrucciones, señalará controles y esperará que el usuario complete las acciones. No ejecutará modificaciones por él ni inventará tutoriales.

**Implementado en E1:** once tutoriales, textos, parámetros, destinos lógicos, condiciones declarativas y un recorrido de bienvenida. **E2:** burbuja y fichas, probadas por el usuario. **E3.1/E3.2:** registro de controles y refs de React; las fichas permiten ubicar y resaltar controles visibles del contexto actual. **Pendiente:** motor que compruebe condiciones, narración y resolución de peticiones. Los recorridos siguientes siguen siendo el contrato de ejecución futura. El catálogo mantiene `runtimeAvailable: false`; `integration: 'registered'` sólo acredita que se instrumentaron controles, no que señalar uno complete un paso.

Fuentes del contenido actual:

**Avance E3.3:** el motor independiente ya evalúa las condiciones de los once tutoriales, con evidencia local y validación de referencias/revisiones. Está probado con observaciones simuladas. Sigue pendiente en E3.4 conectarlo a acciones reales y al panel; por tanto los recorridos todavía no se activan en la web. El contrato concreto está en `Frontend/src/features/tutor/README.md`, sección E3.3.

- [Catálogo de tutoriales](Frontend/src/features/tutor/content/catalog.ts).
- [Controles y archivos donde se encuentran](Frontend/src/features/tutor/content/targets.ts).
- [Recorrido de bienvenida](Frontend/src/features/tutor/content/firstDiagram.ts).
- [Contratos de contexto, progreso y evidencia](Frontend/src/features/tutor/types/tutorial.types.ts).
- [Mensajes de ayuda y límites](Frontend/src/features/tutor/content/messages.ts).
- [Reglas técnicas del módulo](Frontend/src/features/tutor/README.md).
- [Plan general y seguimiento](PLAN-TUTOR-LOGICDRAFT.md).

## 1. Cómo sabrá cuándo actuar

Hay tres decisiones distintas: **elegir un tutorial**, **mostrar su paso** y **dar ese paso por cumplido**. Cada decisión tendrá una comprobación propia.

### Elegir el tutorial

El tutor tendrá dos entradas:

1. **Bienvenida:** después de un registro exitoso, ofrecer «Aprende a crear tu primer diagrama». El usuario elige comenzar o posponer. No hablar automáticamente ni confundir ausencia de preferencias locales con una cuenta nueva.
2. **Ayuda solicitada:** el usuario abre la burbuja, elige un tutorial o escribe qué quiere aprender. El selector identifica los objetivos y consulta el catálogo. Los ejemplos de preguntas de este documento todavía no son un reconocedor implementado.

La pantalla actual por sí sola no inicia un tutorial ni una narración. Sirve para decidir el punto de entrada cuando el usuario solicita ayuda.

### Mostrar el paso

Antes de resaltar un control y hablar, el motor comprobará:

- Que existe un recorrido iniciado y no está pausado.
- Que el usuario, workspace y proyecto corresponden al recorrido.
- Que los datos necesarios están elegidos y siguen existiendo.
- Que el rol permite la acción y no existe un bloqueo de otro colaborador.
- Que está en la pantalla correcta y el control está montado, visible y disponible.

Si algo falta, pedirá una elección, ofrecerá un tutorial previo o pausará. No adivinará IDs ni inventará pasos. La autorización definitiva de cualquier acción seguirá en Spring.

### Completar el paso

El motor observará estado y eventos semánticos de la aplicación. Un clic, el fin del audio o un temporizador no prueban por sí solos que la acción tuvo éxito. Primero se comprobará la condición específica descrita en cada ficha.

## 2. Contexto y navegación

| Ubicación | Información que aporta | Ejemplo ante «¿Cómo creo una clase?» |
| --- | --- | --- |
| Dashboard | Workspaces accesibles y elección del usuario | Guiar a un workspace, elegir/crear proyecto y abrir editor |
| Proyectos de un workspace | Workspace actual, rol y proyectos disponibles | Elegir un proyecto y abrirlo; si no hay uno, ofrecer crearlo |
| Editor | Proyecto, selección, permisos, estado de guardado y elementos del ejercicio | Ir al tutorial Crear una clase UML |
| Otra pantalla | Ruta actual sin un tutorial de entrada registrado | Ofrecer volver a un punto conocido; no fabricar una guía |

«Workspace» es el ámbito de la lista de proyectos; no se asume una pantalla independiente que no existe. Los identificadores se toman de rutas y datos de la aplicación, no de una captura de pantalla ni de una conjetura del modelo.

Los prerrequisitos son **condicionales**: si ya está en el editor del proyecto elegido, no debe volver al dashboard. Abrir un editor tampoco obliga a crear un proyecto nuevo.

## 3. Roles y datos necesarios

- `OWNER` y `EDITOR`: pueden realizar los ejercicios de edición en sus proyectos autorizados.
- `VIEWER`: en el catálogo actual puede abrir workspace y editor. No puede realizar los tutoriales que cambian datos. Una futura modalidad explicativa de sólo lectura se diseñará aparte.
- Si no se conoce el rol, no se asume permiso de edición.
- Los nombres y tipos se eligen antes de comenzar el tutorial que los necesita. En la bienvenida hay valores de ejemplo fijos; no se usarán automáticamente para peticiones libres.

Para recordar elementos se usarán **referencias por ID**. Por ejemplo, después de crear Cliente, el siguiente tutorial recibe el ID capturado, no busca cualquier clase llamada Cliente.

## 4. Fichas de los once tutoriales

### 1. Abrir un workspace — `choose_workspace`

**Se propone para:** «¿Dónde están mis proyectos?» o «¿Cómo abro un workspace?», y como entrada de la bienvenida.

**Requisitos:** pantalla Dashboard, usuario autorizado y workspace elegido. Permite los tres roles. Si hay varios espacios, preguntar cuál usar.

| Paso | Control que señalará | Cuándo avanza |
| --- | --- | --- |
| Pulsar Abrir workspace | Enlace de la tarjeta elegida (`workspace.open`) | La ruta y el contexto muestran la lista de proyectos de ese mismo workspace |

No basta con que se abra cualquier workspace. No crea un espacio nuevo.

### 2. Crear un proyecto — `create_project`

**Se propone para:** «¿Cómo creo un proyecto?» o «Quiero empezar un diagrama nuevo».

**Requisitos:** lista de proyectos del workspace elegido, rol OWNER/EDITOR y nombre de proyecto definido.

| Paso | Control que señalará | Cuándo avanza |
| --- | --- | --- |
| Pulsar Nuevo proyecto | `project.new` | El formulario y su campo de nombre están disponibles |
| Escribir el nombre elegido | `project.name` | El valor del campo coincide con el parámetro `projectName` |
| Pulsar Crear proyecto | `project.create` | La API confirma la creación local y se captura el ID del proyecto del workspace correcto |

La descripción es opcional. Un error de red o de permisos mantiene el paso pendiente. El ID obtenido se entrega al tutorial Abrir el editor.

### 3. Abrir el editor — `open_editor`

**Se propone para:** «¿Cómo entro al editor?» o como prerrequisito de una acción en el diagrama.

**Requisitos:** workspace y proyecto elegidos, acceso permitido. Admite VIEWER para navegación.

| Paso | Control que señalará | Cuándo avanza |
| --- | --- | --- |
| Pulsar Abrir en la tarjeta del proyecto | `project.open` | La aplicación entra al editor de ese proyecto y carga su contexto |

Si no existe un proyecto elegido, pedir selección. Si no hay proyectos, ofrecer Crear un proyecto; no hacerlo automáticamente.

### 4. Crear una clase UML — `create_class`

**Se propone para:** «¿Cómo creo una clase?» o «Agregar una clase UML».

**Requisitos:** editor de un proyecto donde pueda editar. No exige un nombre: renombrar es otro tutorial.

| Paso | Control que señalará | Cuándo avanza |
| --- | --- | --- |
| Abrir Herramientas, si está cerrado | `editor.tools` | El botón Clase UML queda disponible |
| Pulsar Clase UML | `editor.class` | Se observa una creación local y existe el nuevo nodo de tipo CLASS en el proyecto |

Se captura su ID como `node`. Un nodo anterior, un nodo creado por otro colaborador o una entidad de tipo ENTITY no cumplen el ejercicio.

### 5. Crear una entidad de datos — `create_entity`

**Se propone para:** «¿Cómo creo una entidad?» o «Agregar entidad de datos».

**Requisitos:** editor y permiso de edición.

| Paso | Control que señalará | Cuándo avanza |
| --- | --- | --- |
| Abrir Herramientas, si está cerrado | `editor.tools` | El botón Entidad queda disponible |
| Pulsar Entidad | `editor.entity` | Se observa una creación local y existe un nuevo nodo ENTITY, incluido el formato existente donde kind omitido representa una entidad |

Se captura el ID del nodo. El tutor no sustituirá silenciosamente una clase UML por una entidad de datos ni a la inversa.

### 6. Cambiar el nombre — `rename_node`

**Se propone para:** «¿Cómo cambio el nombre de una clase?».

**Requisitos:** proyecto, nodo concreto, permiso de edición y nuevo nombre `nodeName`.

| Paso | Control que señalará | Cuándo avanza |
| --- | --- | --- |
| Seleccionar el nodo | `editor.node` | El ID seleccionado coincide con el nodo del ejercicio |
| Abrir Propiedades, si está cerrado | `editor.properties` | El campo de nombre del nodo correcto está disponible |
| Cambiar el nombre | `node.name` | El estado de ese nodo contiene el nombre esperado |

Si el nombre ya coincide, informar «ya satisfecho» sin atribuir una edición nueva. Si se elimina el nodo o cambia el proyecto, pausar.

### 7. Añadir un atributo y elegir su tipo — `add_attribute`

**Se propone para:** «¿Cómo agrego un atributo?» o «Añadir un campo a una clase».

**Requisitos:** nodo concreto, permiso de edición, nombre `attributeName` y tipo `attributeType` permitido por el editor.

| Paso | Control que señalará | Cuándo avanza |
| --- | --- | --- |
| Seleccionar el nodo | `editor.node` | Coincide el ID seleccionado |
| Abrir Propiedades | `editor.properties` | Los controles del nodo están disponibles |
| Pulsar Agregar en Atributos | `attribute.add` | Se crea localmente un atributo en ese nodo; se captura su ID |
| Escribir el nombre | `attribute.name` | El atributo capturado contiene el nombre esperado |
| Seleccionar el tipo | `attribute.type` | Ese mismo atributo contiene el tipo esperado |

Ejemplos de tipos visibles: INTEGER y VARCHAR. No confundirlos con los nombres del contrato de IA, como Integer o String. Un atributo homónimo en otra clase no cuenta.

### 8. Marcar una clave primaria — `set_primary_key`

**Se propone para:** «¿Cómo marco una clave primaria?» o «¿Dónde está Primary Key?».

**Requisitos:** nodo y atributo perteneciente a él, permiso de edición y control editable.

| Paso | Control que señalará | Cuándo avanza |
| --- | --- | --- |
| Seleccionar el nodo | `editor.node` | Coincide el ID seleccionado |
| Abrir Propiedades | `editor.properties` | Están disponibles sus propiedades |
| Marcar Primary Key en la fila elegida | `attribute.pk` | `primaryKey` es verdadero en el atributo correcto |

No forzar atributos estructurales deshabilitados ni bloqueos de colaboradores. Marcar la clave en una clase UML no activa «Generar CRUD»; esa función no forma parte de esta lección.

### 9. Relacionar dos clases o entidades — `create_relationship`

**Se propone para:** «¿Cómo conecto dos clases?» o «Crear una asociación».

**Requisitos:** proyecto editable, nodos de origen y destino existentes, distintos, compatibles y elegidos por ID. No deben estar bloqueados para la operación.

| Paso | Control que señalará | Cuándo avanza |
| --- | --- | --- |
| Abrir Herramientas | `editor.tools` | La opción de asociación está disponible |
| Elegir ASSOCIATION | `relation.mode` | El modo de relación seleccionado es ASSOCIATION |
| Arrastrar un conector desde origen hasta destino | `relation.connect` | Se crea localmente una asociación que une exactamente los nodos elegidos |

Se captura el ID de la relación. Mover un nodo, seleccionar dos nodos o crear herencia no completa el paso.

### 10. Configurar cardinalidad — `configure_cardinality`

**Se propone para:** «¿Cómo cambio la cardinalidad?» o «¿Dónde pongo uno a muchos?».

**Requisitos:** relación elegida por ID, permiso de edición y valores de origen/destino definidos. La relación debe admitir edición de multiplicidades.

| Paso | Control que señalará | Cuándo avanza |
| --- | --- | --- |
| Seleccionar la relación | `relation.select` | Se selecciona el ID esperado |
| Abrir Propiedades | `editor.properties` | Está disponible el control Cardinalidad origen |
| Configurar origen | `relation.source` | `sourceCardinality` coincide con el valor esperado |
| Configurar destino | `relation.target` | `targetCardinality` coincide con el valor esperado |

Los valores se muestran y narran con las etiquetas ya existentes: ZERO_ONE → 0..1, ONE_ONE → 1..1, ZERO_MANY → 0..N y ONE_MANY → 1..N. No se leen códigos internos en voz alta. Origen y destino se determinan por IDs, no por qué nodo está a la izquierda.

Si la petición no permite saber qué extremo debe llevar cada valor, pedir aclaración. Este tutorial enseña dónde configurarlo, no evalúa por sí solo la corrección conceptual del modelo.

### 11. Comprobar el guardado — `verify_save`

**Se propone para:** «¿Cómo guardo mi diagrama?» o «¿Cómo sé si se guardó?», y al final de la bienvenida.

**Requisitos:** proyecto correcto y permiso de edición. El motor debe conocer las revisiones locales pendientes.

| Paso | Control que señalará | Cuándo avanza |
| --- | --- | --- |
| Esperar guardado automático o pulsar Guardar | `diagram.save` | El servidor confirma una revisión que cubre todos los cambios locales pendientes |

No basta con pulsar Guardar ni con recibir un aviso de guardado de otro usuario. Si llega una respuesta de una revisión anterior y existen cambios posteriores, seguir esperando. Ante conflicto 409, documento inválido 422, error de red o pérdida de permisos, pausar sin declarar éxito.

## 5. Orden de la bienvenida

El recorrido `first_diagram` utiliza trece instancias de los once tutoriales disponibles. Repite algunos y no utiliza Crear entidad, porque el ejercicio trabaja con clases UML.

| Orden | Tutorial | Valores o referencia utilizada |
| --- | --- | --- |
| 1 | Abrir workspace | Workspace elegido por el usuario |
| 2 | Crear proyecto | Mi primer diagrama |
| 3 | Abrir editor | Proyecto recién creado |
| 4 | Crear clase UML | Capturar primer nodo |
| 5 | Cambiar nombre | Primer nodo → Cliente |
| 6 | Añadir atributo | Cliente → id, INTEGER |
| 7 | Marcar clave primaria | Atributo id recién creado |
| 8 | Añadir atributo | Cliente → nombre, VARCHAR |
| 9 | Crear clase UML | Capturar segundo nodo |
| 10 | Cambiar nombre | Segundo nodo → Pedido |
| 11 | Crear asociación | Cliente como origen y Pedido como destino |
| 12 | Configurar cardinalidad | Origen 1..1 y destino 0..N |
| 13 | Comprobar guardado | Revisiones locales del ejercicio confirmadas |

Los nombres del ejercicio son ejemplos predefinidos. Fuera de la bienvenida se utilizarán los parámetros pedidos o elegidos por el usuario. El instructor no creará nada por adelantado.

## 6. Cómo encadenará una petición con varios objetivos

Ejemplo: «Enséñame a crear dos clases, ponerles nombres y relacionarlas».

1. Reconocer los tres objetivos y la cantidad de clases.
2. Consultar la pantalla y resolver el acceso al editor si hace falta.
3. Pedir los nombres si no se proporcionaron.
4. Mostrar la cola: crear clase, renombrar, crear segunda clase, renombrar y relacionar.
5. Conservar IDs distintos para cada creación aunque ambas usen `create_class`.
6. Usar esos IDs como extremos de la asociación.
7. No anunciar toda la petición como completada hasta cubrir sus objetivos.

Si sólo hay tutorial para parte de lo solicitado, indicar qué parte falta y ofrecer comenzar la disponible. Si «Omitir» deja pendiente la creación de un objeto necesario, pedir selección o completar el paso anterior; no seguir con una referencia inexistente.

## 7. Tabla técnica de condiciones

| Condición del contrato | Evidencia requerida | Lo que no basta |
| --- | --- | --- |
| `screen` | Ruta y ámbito coinciden con workspace/proyecto elegido | Clic en un enlace |
| `control-ready` | Control correcto montado, visible y disponible | Esperar unos segundos |
| `input-matches` | Campo de formulario contiene el parámetro esperado | Haber escrito en otro campo |
| `created` | Acción local del paso, objeto presente y pertenencia correcta; confirmación API para proyecto | Elemento preexistente o cambio remoto |
| `selected` | ID seleccionado coincide con la referencia del tutorial | Seleccionar cualquier nodo o arista |
| `field-matches` | Campo del objeto correcto coincide con el valor esperado | Coincidencia de nombre en otro objeto |
| `primary-key-enabled` | Atributo ligado al paso tiene la marca activa | Marcar otro atributo |
| `relation-kind` | Modo actual ASSOCIATION | Abrir Herramientas |
| `saved-revision` | Confirmación del servidor cubre todos los cambios locales | Audio finalizado, clic o guardado remoto |

Las condiciones no son código que venga del modelo. E3 implementará evaluadores registrados y adaptadores a eventos de React/API. Los textos no contienen JavaScript ejecutable.

## 8. Cuándo debe esperar, pausar o responder sin iniciar una guía

| Situación | Comportamiento previsto |
| --- | --- |
| Usuario aún no hizo la acción | Mantener el paso, permitir pista o repetir narración |
| Panel ya abierto o valor ya correcto | Reconocer estado satisfecho, sin simular una edición |
| Falta proyecto, nodo o atributo elegido | Pedir elección o proponer un prerrequisito conocido |
| Usuario cambia a otro proyecto | Pausar y revisar el contexto |
| Colaborador modifica un elemento | Actualizar contexto sin adjudicar la acción al alumno |
| Elemento eliminado o bloqueado | Pausar; no operar sobre referencias obsoletas |
| Control ausente o deshabilitado | Explicar requisito faltante; no resaltar un destino inexistente |
| Usuario cierra el panel o sale de sesión | Detener voz; limpiar lo correspondiente a la sesión |
| Error o conflicto de guardado | No completar el recorrido; orientar a resolver el aviso existente |
| Función de LogicDraft sin tutorial | «Todavía no tengo un tutorial para esa función» |
| Petición ajena a la plataforma | Recordar que el instructor enseña el uso de LogicDraft |
| Pregunta de teoría de modelado | Indicar que esas lecciones todavía no están disponibles |
| Petición no comprendida | Aclarar u ofrecer catálogo antes de declararla fuera de alcance |

## 9. Papel de la IA y consumo

La decisión de avanzar un paso pertenece al motor determinista. No se consulta a un modelo para saber si apareció un botón, si se creó un nodo o si se guardó el documento.

La selección de tutoriales se resolverá primero con reglas locales. Si más adelante hace falta un clasificador con Ollama o Gemini, sólo devolverá IDs y parámetros permitidos. No redactará tutoriales, no hablará libremente ni generará operaciones de diagrama. No se llamará al modelo por clic, por paso ni al mover el robot.

La voz inicial leerá los textos del catálogo mediante el navegador. Cuando termine de hablar, seguirá esperando la acción si aún no se cumplió.

## 10. Qué se puede revisar ahora

En E1 se puede inspeccionar el contenido y comprobar su consistencia:

```powershell
cd Frontend
npm.cmd run test:tutor
```

La suite comprueba referencias, dependencias, contratos, parámetros, separación de clases/entidades y consistencia del recorrido. No prueba interacción visual, voz, permisos efectivos del servidor ni ejecución real de los pasos: esas comprobaciones llegarán con sus etapas.

**Antes de publicar cada tutorial en la interfaz**, verificar el control real, adaptar su evidencia, probarlo con permisos y contexto distintos y actualizar este documento si cambia el comportamiento. El catálogo y esta descripción deben permanecer sincronizados.
