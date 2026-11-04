# Imagen aditiva — image-v1

Alcance: reconocimiento visual y altas UML. No cambia el contrato de operaciones
del agente textual, el CRUD manual, XMI ni los generadores. No implementa Fase 2.

## Reconocimiento (FastAPI)

`POST /api/ai/image/interpret` recibe multipart `image`, `prompt`, `diagram`.
`diagram` es el documento autorizado completo `{version: 1|2, nodes, edges}`,
no la proyeccion entidad-relacion del agente textual. Limite de imagen: 5 MiB;
PNG, JPEG, WEBP con verificacion de firma. No se almacena el archivo.

Respuesta: `{operations: [...], warnings: [...]}`. Esquema cerrado y validado en
Python y Java, con un maximo de 100 observaciones. Las unicas operaciones son:

- `ADD_CLASSIFIER`: `name`, `kind` (o null si incierto), `attributes`, `methods`,
  `literals`, `abstract` opcional. Describe tambien clasificadores existentes.
- `ADD_RELATIONSHIP`: `source`, `target` por nombre, `relationKind`, `name`,
  `sourceMultiplicity`, `targetMultiplicity`, `aggregationEnd` opcionales.

Los atributos tienen `name`, `type`, y opcionalmente `primaryKey`, `nullable`,
`visibility`, `static`. Los metodos tienen `name`, `returnType`, `parameters`
(`name`, `type`) y modificadores opcionales. Las propiedades desconocidas,
incluidos IDs, posiciones y cualquier DELETE/UPDATE/RENAME/REPLACE, se rechazan.
Los valores opcionales null representan informacion no observada.

No son operaciones ejecutables del agente general: Spring decide las altas.
Se conservan kind, nombres, IDs y miembros existentes; una imagen parcial nunca
los elimina. Coincidencias inequivocas reutilizan sus IDs. Nombres normalizados
ambiguos, tipos, modificadores o multiplicidades incompatibles bloquean TODO el
lote, incluso si contiene otras altas validas. No hay aplicacion parcial.

## Propuesta y confirmacion (Spring/React)

`POST /api/proyectos/{projectId}/ai/image/interpret` conserva el multipart publico
`image`, `prompt`. Su respuesta cambia del contrato general de operaciones a:

```json
{
  "proposalId": "UUID generado por el servidor",
  "baseRevision": 4,
  "matched": ["Cliente [id-existente]"],
  "additions": ["Atributo Cliente.correo"],
  "conflicts": [],
  "warnings": [],
  "canApply": true
}
```

`baseRevision` es null solo si no existe un documento persistido. React muestra
las cuatro listas, pero no modifica el canvas al recibirlas ni al cancelar.
No entrega a este flujo respuestas del agente textual.

`POST /api/proyectos/{projectId}/ai/image/accept` recibe unicamente
`{proposalId, clientId}` y devuelve el `DiagramaResponse` confirmado. Spring
conserva el candidato; no confia en documentos ni operaciones reenviados por
React. Propuestas ligadas a usuario, proyecto y revision; caducan a los 10 minutos.
Cache limitada a 100 propuestas por instancia, sin almacenar imagen ni prompt.
Reinicio/caducidad exige repetir el analisis; no afecta documentos guardados.
No es almacenamiento distribuido: una futura arquitectura multiinstancia
necesitara afinidad de instancia o un almacen compartido autorizado.

La confirmacion transaccional revalida OWNER/EDITOR, revision exacta, locks y
documento completo. Reutiliza `DiagramaService.guardarSinRebase`, que excluye la
combinacion automatica de revisiones del editor ordinario. El guardado ordinario
conserva su comportamiento. Una sola escritura/flush y el publicador colaborativo
existente AFTER_COMMIT. 409 exige recalcular; 422 indica contrato/documento invalido
o una propuesta bloqueada/sin altas; 403 deniega permisos. Un fallo no aplica
cambios optimistas al canvas. Ante respuesta de red perdida, sincronizar antes de
reintentar: la revision impide aplicar dos veces la misma propuesta.

## Compatibilidad y frontera deliberada

- Documentos v1 se mantienen v1 al agregar atributos compatibles; las nuevas
  estructuras UML promueven explicitamente a v2 sin reinterpretar nodos existentes.
- Ninguna CLASS se convierte en ENTITY ni activa `generateCrud` automaticamente.
- Clases asociativas existentes conservan sus metadatos y enlaces. Crear una
  nueva ASSOCIATION_CLASS requiere vincular su asociacion explicitamente: se
  muestra advertencia/conflicto; no se inventan FKs ni conectores.
- Se admiten los seis tipos de relaciones UML existentes. Una relacion nueva
  multiplicativa requiere ambos extremos legibles; agregacion/composicion requiere
  el extremo del diamante. Se preservan referencias y relaciones originales.
- Tipos de atributo restringidos al contrato persistido. String/VARCHAR,
  Long/BIGINT, Int/INTEGER, Bool/BOOLEAN y DateTime/TIMESTAMP son equivalencias
  soportadas. Double/Float, tipos de clasificador como atributo u otros tipos
  no representables se informan y bloquean, sin convertirlos silenciosamente.
- Sobrecargas ambiguas y relaciones paralelas cuyo nombre/identidad no se pueda
  resolver se bloquean. No se inventan operaciones de reemplazo para resolverlas.
- La interpretacion visual sigue dependiendo de Gemini. No incorpora Qwen,
  cambios de audio, descarga de modelos ni infraestructura.

## Validacion

Regresiones en `ImageDiagramReconcilerTest`, `ImageInterpretationServiceTest`, `ImageProposalServiceTest`,
`test_image_additive.py` e `imageProposal.test.mjs`. Los tests usan proveedores y
persistencia simulados, incluidos servicios reales bajo transacciones Spring.
El reconocimiento con imagen real y la prueba visual multiusuario requieren QA
manual; no se sustituyen por estas pruebas.
