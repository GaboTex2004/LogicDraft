# Agente contextual V1

`POST /api/agent/ask` recibe la pregunta y un contexto estructurado autorizado por
Spring: proyecto, diagrama, entidades, atributos, relaciones, cardinalidades,
seleccion y hasta 25 eventos recientes. Pydantic rechaza campos extra y eventos
desconocidos.

La respuesta usa siempre un contrato JSON:

```json
{"answer":"Relacion N:M propuesta.","operations":[{"type":"ADD_RELATIONSHIP","relationship":{"sourceEntity":"Alumno","targetEntity":"Materia","sourceCardinality":"ZERO_MANY","targetCardinality":"ZERO_MANY","name":"materias","joinTableName":"alumno_materia"}}]}
```

Las consultas factuales sobre cantidades, nombres, atributos, primary keys,
relaciones y seleccion se resuelven deterministicamente sin Ollama y devuelven
`operations: []`. Una solicitud de cambio puede proponer solo `ADD_ENTITY`,
`ADD_ATTRIBUTE` o `ADD_RELATIONSHIP`. No existen operaciones destructivas, SQL ni
codigo ejecutable.

El agente entiende N:M como cardinalidad many en ambos extremos. Para una N:M
simple propone un edge. Si se piden atributos propios, propone una entidad
asociativa explicita y dos relaciones; no inventa columnas. Los nombres opcionales
de relacion y tabla intermedia forman parte del mismo contrato del editor.

Spring recupera el diagrama persistido, valida referencias, duplicados,
cardinalidades y autorrelaciones, y devuelve solo operaciones validas. El frontend
las valida otra vez y las aplica por el flujo normal de dirty/autosave/colaboracion.
El modelo nunca persiste directamente ni recibe credenciales. Una recomendacion
sin operaciones no cambia el diagrama.
