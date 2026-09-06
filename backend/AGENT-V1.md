# Agente contextual V1

`POST /api/proyectos/{projectId}/agent/ask` requiere JWT y acepta una pregunta,
`selectedNodeId`, `selectedEdgeId` y hasta 25 eventos recientes. OWNER, EDITOR y
VIEWER pueden consultar cuando `WorkspaceAccessService.verificarAcceso` confirma
su membresía; un usuario externo recibe 403.

Spring no acepta un grafo enviado por el navegador. Recupera el proyecto y su
diagrama persistido, valida el workspace y construye un contexto semántico con
entidades, atributos, relaciones y cardinalidades. Las posiciones y demás datos
visuales se excluyen. Una selección inexistente se convierte en `null` y un
proyecto todavía sin diagrama produce listas vacías válidas.

Después se llama a `POST {AI_SERVICE_URL}/api/agent/ask`. La respuesta pública es
`{"answer":"..."}`. Este flujo es de solo lectura: no usa `DiagramaService.guardar`,
no publica eventos y no aplica operaciones `ADD_*`. Los errores del proveedor se
traducen con las mismas reglas 502/503/504 del cliente de IA existente.
