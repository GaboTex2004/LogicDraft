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

Las conexiones guardan data.relationshipType con ONE_TO_ONE, ONE_TO_MANY,
MANY_TO_ONE o MANY_TO_MANY. Mantienen la flecha existente: no se ha implementado
un renderer de notacion avanzada de cardinalidades. Edges antiguos sin data son
compatibles y su cardinalidad se considera desconocida, no una relacion identica
a una cardinalidad concreta. La direccion source/target importa.

Las entidades nuevas se colocan en una columna libre debajo de los nodos
existentes, reservando altura segun sus atributos. Ajustar vista se ejecuta
despues de crear entidades para hacerlas visibles.

## Guardado y colaboracion

Al completar el lote, se actualiza React Flow y se llama una vez a markDirty.
El debounce existente guarda por HTTP PUT; no hay una segunda persistencia de IA.
Se emiten NODE_CREATED, NODE_UPDATED y EDGE_CREATED por publishEvent existente.
Los receptores conservan nullable y data.relationshipType y no reemiten eventos.
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
   En la respuesta y en el documento guardado, comprobar relationshipType.
8. Abrir el mismo proyecto con un segundo usuario EDITOR en otro navegador:
   repetir los pasos y comprobar la aparicion por eventos normales de WebSocket.
9. Esperar Guardado, recargar ambas sesiones y comprobar nodos, atributos y edges.
10. Probar una entidad inexistente, una entidad duplicada y un atributo de tipo
    incompatible: debe aparecer error o no-op segun la propuesta validada, nunca
    una aplicacion parcial. Probar VIEWER: Spring debe devolver 403.

La verificacion automatizada del aplicador no sustituye esta prueba con dos
sesiones autenticadas y Ollama real.
