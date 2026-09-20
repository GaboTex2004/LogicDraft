# ApplicationSchema V1

`ApplicationSchema` es el contrato semantico e independiente de UI que alimentara a los futuros
generadores. El flujo previsto es:

```text
Diagrama persistido -> ApplicationSchema -> Spring Boot + PostgreSQL
                                         -> Flutter (cliente REST)
```

Esta fase termina en la construccion y validacion del schema. No genera codigo, archivos, ZIP,
SQL, proyectos Spring, proyectos Flutter ni llama servicios de IA.

## Contrato

- `schemaVersion`: version del contrato, actualmente `1`.
- `projectName` y `applicationName`: nombre visible del proyecto.
- `technicalName`: identificador tecnico normalizado de la aplicacion.
- `entities`: entidades con ID estable del nodo, nombre visible, nombre tecnico y campos.
- `fields`: ID estable del atributo, nombres, tipo canonico, PK, nullability y `generated` explicito.
- `relationships`: ID estable del edge, IDs de ambos extremos, sus dos cardinalidades y los campos
  opcionales `name` y `joinTableName`.

No se incluye `basePackage` en V1 porque es una decision propia del futuro generador Spring y no
un concepto compartido con Flutter.

## Tipos canonicos

| Entradas actuales del editor | ApplicationSchema |
| --- | --- |
| `VARCHAR`, `TEXT`, `String` | `STRING` |
| `INTEGER`, `INT`, `Integer` | `INTEGER` |
| `BIGINT`, `LONG`, `Long` | `LONG` |
| `DECIMAL`, `DOUBLE`, `FLOAT`, `Double` | `DECIMAL` |
| `BOOLEAN`, `BOOL`, `Boolean` | `BOOLEAN` |
| `DATE`, `Date` | `DATE` |
| `TIMESTAMP`, `DATETIME`, `DateTime` | `DATETIME` |

Los futuros generadores traduciran estos conceptos. Por ejemplo, `DECIMAL` podra convertirse en
`BigDecimal`, `NUMERIC` y `double` para Spring, PostgreSQL y Flutter respectivamente.

El documento real guarda el tipo en `data.attributes[].type` (no `dataType`). Por compatibilidad
con documentos V1 ya persistidos, si `nullable` no existe se aplica la misma regla vigente del
editor/contexto: una PK es no nullable y otro atributo es nullable. Los IDs de nodo, atributo y edge
si son obligatorios para un schema generable, porque constituyen sus referencias estables.

## Primary keys

Cada entidad debe tener exactamente una PK simple. Una PK nunca puede ser nullable. V1 marca
`generated=true` solamente para PK de tipo `INTEGER` o `LONG`; las PK de otros tipos se conservan
pero requieren valor suministrado (`generated=false`). Esta es una convencion de generacion
explicita y todavia no implica emitir `@GeneratedValue`.

## Naming

La normalizacion central elimina acentos, transforma `ñ` en `n`, separa espacios/camel case,
descarta caracteres no alfanumericos y genera PascalCase para aplicaciones/entidades y lowerCamelCase
para campos. Un identificador que comienza con numero recibe el prefijo `N` o `n`. Nombres vacios
o sin caracteres utilizables fallan. Las colisiones resultantes, como `Categoria` y `Categoría`,
son errores y nunca se resuelven silenciosamente.

## Relaciones y validacion

Se preservan independientemente `ZERO_ONE`, `ONE_ONE`, `ZERO_MANY` y `ONE_MANY` para cada extremo.
El validador comprueba entidades, IDs, nombres tecnicos, PK simple, atributos case-insensitive,
tipos, nullability de PK, referencias de relaciones, cardinalidades y autorrelaciones. Los documentos
legacy con `relationshipType` se normalizan igual que en LogicDraft; los documentos actuales usan
`sourceCardinality` y `targetCardinality`.

Una relacion es N:M cuando ambos extremos son `ZERO_MANY` o `ONE_MANY`; no se deduce de la posicion
visual. `name` permite nombrar el campo o rol semantico y distinguir dos relaciones entre las mismas
entidades. `joinTableName` permite fijar la tabla intermedia. Ambos son opcionales para conservar
compatibilidad con documentos anteriores. Dos relaciones equivalentes sin nombres se rechazan; dos
relaciones nombradas son validas, pero sus tablas derivadas o explicitas deben ser distintas.

La N:M simple sigue siendo un edge y la tabla intermedia es derivada: no se agrega una entidad oculta
al documento. Si la asociacion necesita atributos, se modela una entidad explicita (por ejemplo
`Inscripcion(fecha, nota)`) y dos relaciones hacia ella. El generador no inventa atributos. Las
autorrelaciones se rechazan en V1 porque el naming de sus dos FK aun no forma parte del contrato.

### Metadatos opcionales de entidad asociativa

`ApplicationEntity.association` es opcional y se omite del JSON cuando no existe. Su ausencia conserva
exactamente el significado de los diagramas V1 anteriores; una entidad con dos relaciones no se infiere
como asociativa. Cuando está presente utiliza `kind=MANY_TO_MANY_ASSOCIATION`, un `tableName` físico,
`uniquePair=true` y exactamente dos endpoints `SOURCE`/`TARGET`. Cada endpoint identifica la entidad
externa, la relación estructural y el nombre físico de su foreign key.

Las foreign keys estructurales no son `ApplicationField`: las representan las dos relaciones requeridas,
evitando columnas duplicadas. La entidad asociativa mantiene una PK simple `INTEGER` o `LONG` generada y
sus atributos propios permanecen en `fields`. El validador rechaza endpoints o relaciones inexistentes,
roles/foreign keys repetidos, nombres físicos inválidos, ausencia de unicidad y coexistencia con la N:M
original. El soporte de generación física de esta metadata pertenece a una etapa posterior; no debe
usarse todavía para materializar asociaciones nuevas.

El endpoint de preview es `GET /api/proyectos/{projectId}/generator/schema`. Requiere JWT y reutiliza
`WorkspaceAccessService.verificarAcceso`, por lo que OWNER, EDITOR y VIEWER con lectura actual pueden
acceder y un usuario externo recibe 403. Primero se autoriza el proyecto y despues se lee su diagrama.

## Informacion excluida

No se proyectan posiciones, estilos, handles, viewport, zoom, seleccion, estado de guardado, JWT,
usuarios, tenant, workspace, presencia, STOMP, WebSocket ni eventos del agente. La operacion es
read-only y trabaja con el ultimo diagrama persistido.

## Ejemplo

```json
{
  "schemaVersion": 1,
  "projectName": "Peluqueria",
  "applicationName": "Peluqueria",
  "technicalName": "Peluqueria",
  "entities": [
    {
      "id": "peluqueria",
      "name": "Peluqueria",
      "technicalName": "Peluqueria",
      "fields": [
        {"id":"peluqueria-id","name":"ID","technicalName":"id","type":"INTEGER","primaryKey":true,"nullable":false,"generated":true},
        {"id":"peluqueria-nombre","name":"Nombre","technicalName":"nombre","type":"STRING","primaryKey":false,"nullable":false,"generated":false},
        {"id":"peluqueria-ubicacion","name":"Ubicacion","technicalName":"ubicacion","type":"STRING","primaryKey":false,"nullable":false,"generated":false}
      ]
    },
    {
      "id": "categoria",
      "name": "Categoria",
      "technicalName": "Categoria",
      "fields": [
        {"id":"categoria-id","name":"ID","technicalName":"id","type":"INTEGER","primaryKey":true,"nullable":false,"generated":true},
        {"id":"categoria-nombre","name":"Nombre","technicalName":"nombre","type":"STRING","primaryKey":false,"nullable":false,"generated":false}
      ]
    },
    {
      "id": "corte",
      "name": "Corte",
      "technicalName": "Corte",
      "fields": [
        {"id":"corte-id","name":"ID","technicalName":"id","type":"INTEGER","primaryKey":true,"nullable":false,"generated":true},
        {"id":"corte-nombre","name":"Nombre","technicalName":"nombre","type":"STRING","primaryKey":false,"nullable":false,"generated":false},
        {"id":"corte-precio","name":"Precio","technicalName":"precio","type":"DECIMAL","primaryKey":false,"nullable":false,"generated":false}
      ]
    }
  ],
  "relationships": [
    {"id":"categoria-corte","sourceEntityId":"categoria","targetEntityId":"corte","sourceCardinality":"ONE_ONE","targetCardinality":"ZERO_MANY","name":null,"joinTableName":null}
  ]
}
```

## Limites de V1

- No hay claves compuestas ni autorrelaciones.
- No se validan aun palabras reservadas especificas de Java, Dart o PostgreSQL.
- No hay configuracion de estrategia de IDs distinta de la convencion numerica descrita.
- PostgreSQL sera la base principal del backend Spring generado; SQLite y offline quedan fuera.
- Flutter sera el frontend generado y consumira REST; LogicDraft continua siendo React.
