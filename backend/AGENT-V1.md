# Agente contextual V1

`POST /api/proyectos/{projectId}/agent/ask` requiere JWT y acepta una pregunta,
seleccion opcional y hasta 25 eventos recientes. OWNER, EDITOR y VIEWER pueden
consultar cuando `WorkspaceAccessService.verificarAcceso` confirma su membresia;
un usuario externo recibe 403.

Spring no acepta un grafo enviado por el navegador. Recupera el diagrama persistido
y construye el contexto semantico sin posiciones ni estilos. Una seleccion
inexistente se convierte en `null`; un proyecto sin diagrama produce listas vacias.

El ai-service responde `answer` y `operations`. Las consultas pueden devolver una
lista vacia. Para solicitudes de cambio solo se aceptan `ADD_ENTITY`,
`ADD_ATTRIBUTE` y `ADD_RELATIONSHIP`, incluida N:M con las dos cardinalidades y
los campos opcionales `name` y `joinTableName`.

Si la respuesta contiene operaciones, Spring exige ademas rol OWNER o EDITOR antes
de devolverlas. VIEWER conserva las consultas, pero una solicitud accionable recibe
403 y nunca llega a modificar el canvas.

`DiagramOperationValidator` valida forma y tipos; `ContextualOperationValidator`
valida referencias, duplicados, orden del lote y autorrelaciones contra el contexto
autorizado. Spring no guarda el diagrama desde este endpoint. El frontend recibe
las operaciones validadas, las aplica mediante su mecanismo transaccional normal y
activa autosave y eventos de colaboracion. El agente no ejecuta SQL ni operaciones
destructivas. Los errores del proveedor mantienen las reglas 502/503/504.
