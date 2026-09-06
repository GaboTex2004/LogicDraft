# Agente contextual V1

`POST /api/agent/ask` recibe una pregunta y un contexto estructurado ya autorizado
por Spring. Incluye proyecto, diagrama opcional, entidades/atributos,
relaciones/cardinalidades, selección y hasta 25 eventos recientes. Pydantic prohíbe
campos extra y eventos desconocidos.

`AgentService` crea un prompt breve y textual, prioriza la entidad o relación
seleccionada y reutiliza el mismo `OllamaProvider` sin JSON Schema. El modelo se
invoca únicamente cuando llega una pregunta. La respuesta es texto:

```json
{"answer":"Producto está relacionado con Categoria mediante 1..1 a 0..N."}
```

El prompt restringe el rol a consulta: no genera operaciones `ADD_*`, SQL,
comandos o código ejecutable. Este servicio no recibe JWT ni secretos, no monitoriza
el equipo y no escribe en PostgreSQL. La autorización y la recuperación del
diagrama persistido pertenecen a Spring.
