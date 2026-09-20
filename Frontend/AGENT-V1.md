# Agente contextual V1

El editor incluye un panel de **Agente contextual** separado de `AiPromptBar`.
Las consultas factuales siguen devolviendo texto sin operaciones. Cuando el usuario
solicita una accion, la respuesta puede incluir operaciones estructuradas
`ADD_ENTITY`, `ADD_ATTRIBUTE` y `ADD_RELATIONSHIP`, incluida N:M.

Durante la sesión, `AgentSession` registra eventos producidos dentro del editor
(apertura, selección, creación/edición/eliminación, guardado e IA) en memoria. El
buffer conserva como máximo 25 eventos y no registra movimientos de mouse ni cada
píxel del arrastre; una posición movida se resume como `NODE_UPDATED` al terminar.
Al servidor solo se envían la pregunta, los IDs seleccionados y esos eventos. Los
nodos y relaciones usados por el agente siempre los recupera Spring del diagrama
persistido. Spring valida las operaciones contra ese contexto; el editor vuelve a
validarlas y solo entonces las aplica como un lote, marca dirty, publica eventos y
usa el autosave existente. El modelo no escribe directamente en PostgreSQL.

El panel no observa el teclado global, la pantalla ni otras aplicaciones. No tiene
memoria permanente, voz o visión, y no ejecuta SQL ni
comandos. Las autorrelaciones, operaciones destructivas y cambios fuera del contrato
siguen rechazados.
