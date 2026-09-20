# LogicDraft Frontend

Aplicación React + TypeScript + Vite de LogicDraft.

## Configuración local

Copiar el ejemplo antes de iniciar el frontend:

```powershell
Copy-Item .env.example .env
npm install
npm run dev
```

Variables disponibles:

```dotenv
VITE_API_URL=http://localhost:8081/api
VITE_WS_URL=ws://localhost:8081/ws
```

- `VITE_API_URL` es la URL base HTTP del backend.
- `VITE_WS_URL` es el endpoint WebSocket/STOMP.

`.env` contiene valores locales, está ignorado por Git y no debe guardar secretos: todas las variables `VITE_` quedan expuestas en el bundle del navegador.

En producción se deben sustituir ambas URLs por los dominios públicos correspondientes, usando `https://` y `wss://` cuando exista TLS. Vite incorpora estos valores durante el build, por lo que deben estar configurados antes de ejecutar `npm run build`.
# Relaciones conceptuales

Las relaciones nuevas se guardan en `edge.data` con cardinalidad independiente
en cada extremo:

```json
{
  "sourceCardinality": "ONE_ONE",
  "targetCardinality": "ZERO_MANY"
}
```

Los valores y etiquetas son `ZERO_ONE` (`0..1`), `ONE_ONE` (`1..1`),
`ZERO_MANY` (`0..N`) y `ONE_MANY` (`1..N`). Una conexión manual empieza como
`ONE_ONE` — `ONE_ONE`; al seleccionar el edge, ambos extremos se editan desde el
inspector derecho. El cambio marca el documento como pendiente, usa el autosave
HTTP existente y publica `EDGE_UPDATED`. Un evento remoto actualiza el mismo edge
sin volver a publicarlo.

Al cargar, `relationshipType` legacy se convierte así: `ONE_TO_ONE` →
`ONE_ONE/ONE_ONE`, `ONE_TO_MANY` → `ONE_ONE/ZERO_MANY`, `MANY_TO_ONE` →
`ZERO_MANY/ONE_ONE` y `MANY_TO_MANY` → `ZERO_MANY/ZERO_MANY`. “Many” se mapea a
`ZERO_MANY` porque el formato anterior no expresaba un mínimo obligatorio. Al
guardar nuevamente se persisten las dos cardinalidades nuevas.

Una N:M se crea seleccionando un extremo many en ambos lados. El inspector permite
definir `name` y `joinTableName`; la tabla intermedia es un artefacto derivado y no
un nodo adicional. Dos relaciones entre las mismas entidades deben tener nombres
distintos. Si la asociacion necesita atributos, se crea una entidad asociativa
explicita y dos relaciones.

La aplicación de operaciones IA continúa siendo transaccional en memoria: puede
crear entidades y después relacionarlas dentro del mismo batch. No se agregan
relaciones si falta un extremo, si es una autorrelación nueva o si ya existe una
relación equivalente (incluyendo la misma relación con extremos invertidos).
El agente contextual puede proponer las mismas operaciones estructuradas; el editor
las vuelve a validar y reutiliza dirty, autosave y colaboracion existentes.
