# Spring Boot + PostgreSQL Generator V1

## Flujo

```text
Diagrama persistido -> ApplicationSchema validado -> SpringBootGenerator -> backend.zip
```

El generador es determinista. No usa FastAPI, Ollama, prompts ni codigo libre de un LLM. El endpoint
solo construye texto controlado y un ZIP en memoria; nunca ejecuta Maven, Java, SQL ni el proyecto
producido. La compilacion Maven se ejecuta exclusivamente en tests sobre directorios temporales.

## Endpoint

`POST /api/proyectos/{projectId}/generator/backend` devuelve `application/zip` con
`Content-Disposition: attachment; filename="<technical-name>-backend.zip"`.

Se eligio POST porque crea un artefacto potencialmente costoso aunque no persiste estado. El servicio
autoriza primero `OWNER` o `EDITOR` mediante `WorkspaceAccessService.verificarRol`. `VIEWER` y usuarios
externos reciben 403; sin JWT se obtiene 401. Luego reutiliza `ApplicationSchemaService`, que vuelve a
validar el scope y carga solamente el diagrama persistido.

## Estructura generada

```text
<technical-name>-backend/
├── pom.xml
├── README.md
├── .env.example
├── .gitignore
├── docker-compose.yml
└── src/main/
    ├── resources/application.properties
    └── java/com/logicdraft/generated/<application>/
        ├── <Application>Application.java
        ├── config/CorsConfig.java
        ├── error/GlobalExceptionHandler.java
        ├── error/ResourceNotFoundException.java
        ├── entity/<Entity>.java
        ├── repository/<Entity>Repository.java
        ├── service/<Entity>Service.java
        ├── controller/<Entity>Controller.java
        └── dto/
            ├── <Entity>CreateRequest.java
            ├── <Entity>UpdateRequest.java
            └── <Entity>Response.java
```

El `pom.xml` usa Java 21 y Spring Boot 4.1.1 con Web, Data JPA, Validation, PostgreSQL y Test.
No incluye Security, JWT, WebSocket, Lombok ni dependencias de IA.

## Mapeos

| CanonicalType | Java | PostgreSQL conceptual |
| --- | --- | --- |
| `STRING` | `String` | `VARCHAR` |
| `INTEGER` | `Integer` | `INTEGER` |
| `LONG` | `Long` | `BIGINT` |
| `DECIMAL` | `BigDecimal` | `NUMERIC` |
| `BOOLEAN` | `Boolean` | `BOOLEAN` |
| `DATE` | `LocalDate` | `DATE` |
| `DATETIME` | `LocalDateTime` | `TIMESTAMP` |

Las PK `INTEGER`/`LONG` con `generated=true` usan `GenerationType.IDENTITY`. Una PK no generada
solo recibe `@Id`. Nunca se crea una PK adicional. `nullable` se conserva en `@Column` y en DTOs con
`@NotNull` o `@NotBlank` cuando corresponde.

## Naming compartido por el generador

- Package: `com.logicdraft.generated.<technicalname-en-minusculas>`.
- Artifact/directorio: `<technicalname-en-minusculas>-backend`.
- Base de datos: `<snake_case>_db`.
- Tabla y columnas: snake_case.
- Ruta REST: `/api/<entidad-en-snake_case>` en singular, sin pluralizacion linguistica.
- Archivo ZIP: `<technicalname-en-minusculas>-backend.zip`.

La ruta singular es una convencion deliberadamente simple para que el futuro FlutterGenerator pueda
derivarla mediante la misma regla. Se rechazan identificadores Java y palabras reservadas Java o
PostgreSQL; no se agregan sufijos aleatorios.

## Relaciones JPA y DTOs

Cada `ApplicationRelationship` produce un solo lado propietario:

- one/one: el target es propietario con `@OneToOne` y FK unica hacia source;
- one/many: la entidad del extremo many es propietaria con `@ManyToOne` y FK hacia el extremo one;
- many/one: equivalente, invirtiendo el propietario;
- many/many: source es propietario de una `@ManyToMany` unidireccional con join table.

`ZERO_ONE` frente a `ONE_ONE` controla `optional` y nullability de FK. La cardinalidad minima de una
coleccion `ONE_MANY` se valida en el DTO con `@NotEmpty`, pero PostgreSQL/JPA no pueden garantizar por
si solos que un padre tenga al menos un hijo.

No se generan asociaciones bidireccionales, lo que evita `mappedBy` contradictorios y recursion JSON.
Los controllers nunca exponen entidades JPA: create/update/response usan IDs para relaciones. Los
services resuelven esos IDs mediante repositories y devuelven 404 si una referencia no existe.

Para N:M se genera exactamente un `@JoinTable` con `joinColumns`, `inverseJoinColumns` y una
`@UniqueConstraint` sobre la pareja. No se configura `CascadeType.REMOVE`: PUT reemplaza el `Set` de
asociaciones y quitar un ID elimina la fila intermedia, no la entidad relacionada. `findAllById`
resuelve todas las referencias y compara cantidades para rechazar cualquier ID inexistente antes del
guardado. Una relacion nombrada deriva una tabla distinta; `joinTableName` la fija explicitamente.
Las colisiones con tablas de entidad u otras tablas intermedias se rechazan.

Una entidad asociativa explicita no usa `@ManyToMany`: es una entidad JPA normal con sus campos y dos
lados propietarios `@ManyToOne`. `@Table` conserva el nombre fisico de `ApplicationAssociation`, las
dos `@JoinColumn` usan literalmente los nombres FK de sus endpoints y una `@UniqueConstraint` impide
repetir la pareja. Las FK son obligatorias y no se duplican como atributos escalares. Asi se conservan
fecha, nota u otros datos sin crear otra join table ni habilitar cascadas destructivas.

La exportacion backend admite estas entidades despues de validar toda su estructura. La exportacion
Fullstack permanece bloqueada para ellas hasta que Flutter implemente el contrato correspondiente.

## REST y errores

Cada ruta ofrece:

- `GET /api/<entidad>`
- `GET /api/<entidad>/{id}`
- `POST /api/<entidad>`
- `PUT /api/<entidad>/{id}`
- `DELETE /api/<entidad>/{id}`

El proyecto incluye errores JSON básicos: 400 para validation, 404 para recursos/relaciones ausentes,
409 para integridad de datos y 500 sin stack trace en la respuesta.

## IA runtime CREATE

El endpoint generado `POST /api/ai/commands` solo admite CREATE. El ai-service interpreta texto pero
no ejecuta SQL ni persiste. Spring contrasta entidad, atributos, tipos y relaciones con el
`ApplicationSchema`, detecta en conjunto los campos obligatorios ausentes (sin incluir PK generadas)
y devuelve `NEEDS_CLARIFICATION` con `missingFields` antes de construir el DTO o llamar al servicio.
Las relaciones inexistentes o ambiguas, tipos incorrectos y atributos desconocidos producen mensajes
especificos. Para N:M, `relations` contiene una lista de nombres: Spring exige que cada nombre figure
en el comando, lo resuelve por coincidencia unica y forma un `LinkedHashSet` de IDs. Los registros
relacionados deben existir primero; no se crean implicitamente. READ, UPDATE y DELETE mediante IA
permanecen fuera de alcance.

No hay memoria conversacional en el servidor: ante una aclaracion, Flutter conserva el texto para que
el usuario agregue el dato y reenvie el comando completo. La prueba opt-in
`GeneratedManyToManyPostgresIntegrationTest` materializa y compila un backend, crea un schema
PostgreSQL aleatorio, ejecuta servicios reales, inspecciona tabla, FK, UNIQUE y tipos, y elimina solo
ese schema en `finally`. Se habilita con `LOGICDRAFT_TEST_POSTGRES_URL`,
`LOGICDRAFT_TEST_POSTGRES_USER` y `LOGICDRAFT_TEST_POSTGRES_PASSWORD`.

`GeneratedAssociativeEntityPostgresIntegrationTest` aplica el mismo aislamiento para una entidad
asociativa explicita. Ademas comprueba PK independiente, nombres FK personalizados, atributos propios,
NOT NULL, UNIQUE compuesto, rechazo de parejas duplicadas y eliminacion sin cascada a los extremos.

La normalizacion telefonica es schema-bound: solo nombres telefonicos reconocidos cuyo tipo sea
`STRING`. Une grupos de digitos separados por comas cuando existe una unica evidencia en el comando,
preserva ceros y orden, y rechaza valores inventados o ambiguos. No se aplica a decimales ni a otros
campos. Si `nullable=true`, omitir el telefono sigue siendo valido; el generador no cambia el esquema.

## PostgreSQL y CORS

`application.properties` usa `SERVER_PORT`, `DB_URL`, `DB_USER` y `DB_PASSWORD`, con defaults de
desarrollo, y `spring.jpa.hibernate.ddl-auto=update`. El puerto HTTP por defecto es `8080` y el JDBC
por defecto usa `localhost:5434`; el contenedor PostgreSQL conserva su puerto interno `5432` mediante
el mapeo `5434:5432`. Produccion debe sustituir `ddl-auto=update` por migraciones versionadas.
`docker-compose.yml` contiene solamente PostgreSQL 17. `.env.example` documenta valores pero no se
carga automaticamente. CORS acepta una lista de origenes desde `CORS_ALLOWED_ORIGINS` y no usa `*`.

## Seguridad del ZIP

Los archivos se mantienen en memoria con rutas relativas permitidas. Antes de escribir se rechazan
rutas absolutas, drive letters, backslashes, normalizaciones distintas y cualquier `..`. Todas las
entradas quedan bajo una unica raiz sanitizada. Las entradas se ordenan y usan timestamp fijo para
obtener ZIPs reproducibles. No existen escrituras fuera de un temporal durante pruebas.

## Limitaciones V1

- CRUD de reemplazo completo en PUT; no PATCH.
- Sin paginacion, filtros, autenticacion ni autorizacion en la aplicacion generada.
- Sin Flyway/Liquibase; `ddl-auto=update` es solo para demostracion.
- Asociaciones unidireccionales; no se generan colecciones inversas one-to-many.
- La obligatoriedad minima de colecciones no se puede imponer completamente en la base.
- No soporta PK compuestas ni autorrelaciones, conforme a ApplicationSchema V1.
- No genera Maven Wrapper; se requiere Maven instalado.
- Flutter, SQLite y offline pertenecen a fases posteriores.
