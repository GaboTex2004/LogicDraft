# IA en el editor

La barra inferior envia el prompt usando Axios compartido y su interceptor JWT:
`POST /proyectos/{projectId}/ai/diagram/interpret` relativo a VITE_API_URL.
No envia el documento local ni realiza PUT. Hay que esperar a que el editor
este guardado para que el contexto persistido que consulta Spring sea correcto.

## Archivos

- API: src/features/diagram/api/diagramAiApi.ts.
- Contrato de operaciones: src/features/diagram/types/diagramAi.types.ts.
- Validacion, mapeo y aplicador: src/features/diagram/services/applyDiagramOperations.ts.
- Integracion de estado/autosave/eventos: DiagramEditorPage.tsx.
- UI: components/AiPromptBar.tsx.

El aplicador reutiliza EntityFlowNode, EntityAttribute y DiagramEdge. Valida la
respuesta recibida como unknown, ejecuta sobre copias, y devuelve nodes, edges y
los eventos normales a emitir solo cuando todo el lote termina correctamente.
Los IDs usan crypto.randomUUID; se puede inyectar un generador en pruebas.
Los nombres se comparan sin distinguir mayusculas. Entidades duplicadas, destinos
ausentes/ambiguos y atributos incompatibles rechazan el lote completo.
Atributos identicos y relaciones identicas son no-ops.

## Tipos y compatibilidad

| IA | Editor |
| --- | --- |
| String | VARCHAR |
| Long | BIGINT |
| Integer | INTEGER |
| Double | DECIMAL |
| Boolean | BOOLEAN |
| Date | DATE |
| DateTime | TIMESTAMP |

nullable se conserva como propiedad opcional del atributo; en documentos antiguos
su valor efectivo es !primaryKey. El panel manual existente no incorpora un nuevo
control de nulabilidad en esta etapa.

Las conexiones actuales guardan `sourceCardinality` y `targetCardinality` usando
`ZERO_ONE`, `ONE_ONE`, `ZERO_MANY` o `ONE_MANY`. El renderer muestra `0..1`, `1..1`,
`0..N` o `1..N` en cada extremo. Documentos legacy con `relationshipType` se
normalizan al abrirse. Una N:M tiene un extremo many en ambos lados y no depende
de la posicion visual ni de la direccion del trazo.

El panel de propiedades permite editar los campos opcionales `name` y, para N:M,
`joinTableName`. Esos valores sobreviven guardado, colaboracion y reapertura. Dos
relaciones equivalentes sin nombre son duplicadas; dos relaciones entre las mismas
entidades solo son distintas si tienen nombres distintos. Las autorrelaciones se
rechazan en Generator V1.

## N:M manual, IA y agente

Manualmente conecta dos entidades y selecciona `0..N` o `1..N` en ambos extremos.
La tabla intermedia no se inserta como nodo: se deriva durante la exportacion. Si
necesita atributos, crea una entidad asociativa explicita y dos relaciones.

La barra IA acepta, por ejemplo, `Crea Alumno y Materia y relacionalas de muchos a
muchos`. El agente contextual tambien puede devolver operaciones `ADD_ENTITY`,
`ADD_ATTRIBUTE` y `ADD_RELATIONSHIP`; Spring valida el lote contra el diagrama
persistido y el editor lo aplica con el mismo mecanismo transaccional, autosave y
eventos de colaboracion que la barra IA. Una respuesta textual sin operaciones no
modifica el canvas.

Las entidades nuevas se colocan en una columna libre debajo de los nodos
existentes, reservando altura segun sus atributos. Ajustar vista se ejecuta
despues de crear entidades para hacerlas visibles.

## Guardado y colaboracion

Al completar el lote, se actualiza React Flow y se llama una vez a markDirty.
El debounce existente guarda por HTTP PUT; no hay una segunda persistencia de IA.
Se emiten NODE_CREATED, NODE_UPDATED y EDGE_CREATED por publishEvent existente.
Los receptores conservan nullable, ambas cardinalidades y nombres de relacion, y no reemiten eventos.
Si se pierde WebSocket, se mantienen las limitaciones del modo last-write-wins
existente; esta etapa no agrega una cola de mensajes ni sincronizacion offline.

Se aplica sobre el estado local mas reciente al recibir la respuesta, incluyendo
cambios remotos durante la inferencia. Una referencia que dejo de ser valida
produce error sin modificar parcialmente el grafo. Salir del editor cancela la
peticion pendiente. Envio doble y prompt vacio estan bloqueados.

El frontend no recibe actualmente el rol en ProyectoResponse. Spring sigue siendo
la autoridad: OWNER/EDITOR pueden interpretar, VIEWER recibe 403. Si el editor ya
ha detectado falta de permiso de guardado, se deshabilita el envio.
401 reutiliza el retorno al login; 403 muestra falta de permisos; 409 muestra
el mensaje de conflicto del backend; 502/503/504 muestran error de IA; 500 y
fallos de red muestran un mensaje generico.

## Pruebas

Node 24 disponible en este proyecto permite probar TypeScript sin Vitest/Jest:

```powershell
npm run test:diagram-ai
npm run lint
npm run build
```

## Verificacion manual en navegador

1. Iniciar Ollama, FastAPI y Spring con el endpoint contextual actualizado; iniciar Vite.
2. Entrar con una cuenta OWNER o EDITOR y abrir /proyectos/ID_REAL/editor.
3. Crear Personal con id y nombre manualmente, esperar al estado Guardado.
4. Escribir "Agrega un atributo telefono tipo String a Personal" y pulsar Enter.
5. Comprobar que aparece telefono, luego Guardando y Guardado. Si no existe una
   propuesta aplicable, se muestra "No se encontraron cambios para aplicar".
6. Pedir "Crea una entidad Pedido con id y total"; comprobar la nueva entidad
   y que Ajustar vista la muestra sin superponerla sobre otros nodos.
7. Pedir "Relaciona Personal con Pedido uno a muchos"; comprobar la conexion.
   En la respuesta y en el documento guardado, comprobar ambas cardinalidades.
8. Pedir una N:M entre Alumno y Materia, guardar, recargar y editar su nombre.
9. Abrir el mismo proyecto con un segundo usuario EDITOR en otro navegador:
   repetir los pasos y comprobar la aparicion por eventos normales de WebSocket.
10. Esperar Guardado, recargar ambas sesiones y comprobar nodos, atributos y edges.
11. Probar una entidad inexistente, una entidad duplicada y un atributo de tipo
    incompatible: debe aparecer error o no-op segun la propuesta validada, nunca
    una aplicacion parcial. Probar VIEWER: Spring debe devolver 403.

La verificacion automatizada del aplicador no sustituye esta prueba con dos
sesiones autenticadas y Ollama real.
