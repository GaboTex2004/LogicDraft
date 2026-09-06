# Agente contextual V1

El editor incluye un panel **Agente consultivo** separado de `AiPromptBar`.
`AiPromptBar` solicita modificaciones; el agente responde preguntas sobre el
proyecto y nunca aplica cambios. Solo se consulta al modelo al enviar una pregunta.

Durante la sesión, `AgentSession` registra eventos producidos dentro del editor
(apertura, selección, creación/edición/eliminación, guardado e IA) en memoria. El
buffer conserva como máximo 25 eventos y no registra movimientos de mouse ni cada
píxel del arrastre; una posición movida se resume como `NODE_UPDATED` al terminar.
Al servidor solo se envían la pregunta, los IDs seleccionados y esos eventos. Los
nodos y relaciones usados por el agente siempre los recupera Spring del diagrama
persistido.

El panel no observa el teclado global, la pantalla ni otras aplicaciones. No tiene
memoria permanente, herramientas de escritura, voz o visión, y no ejecuta SQL ni
comandos.
