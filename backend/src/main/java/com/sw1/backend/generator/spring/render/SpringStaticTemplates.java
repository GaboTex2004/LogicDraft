package com.sw1.backend.generator.spring.render;

import com.sw1.backend.generator.schema.ApplicationEntity;
import com.sw1.backend.generator.schema.ApplicationSchema;
import com.sw1.backend.generator.spring.SpringNames;
import java.util.stream.Collectors;

public final class SpringStaticTemplates {
    public static final String SPRING_BOOT_VERSION = "4.1.1";

    private SpringStaticTemplates() {
    }

    public static String pom(String artifact) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
                  <modelVersion>4.0.0</modelVersion>
                  <parent>
                    <groupId>org.springframework.boot</groupId>
                    <artifactId>spring-boot-starter-parent</artifactId>
                    <version>%s</version>
                    <relativePath/>
                  </parent>
                  <groupId>com.logicdraft.generated</groupId>
                  <artifactId>%s</artifactId>
                  <version>0.0.1-SNAPSHOT</version>
                  <properties><java.version>21</java.version></properties>
                  <dependencies>
                    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-web</artifactId></dependency>
                    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-data-jpa</artifactId></dependency>
                    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-validation</artifactId></dependency>
                    <dependency><groupId>org.postgresql</groupId><artifactId>postgresql</artifactId><scope>runtime</scope></dependency>
                    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-test</artifactId><scope>test</scope></dependency>
                  </dependencies>
                  <build><plugins><plugin><groupId>org.springframework.boot</groupId><artifactId>spring-boot-maven-plugin</artifactId></plugin></plugins></build>
                </project>
                """.formatted(SPRING_BOOT_VERSION, artifact);
    }

    public static String properties(String database) {
        return """
                server.port=${SERVER_PORT:8080}
                spring.datasource.url=${DB_URL:jdbc:postgresql://localhost:5434/%s}
                spring.datasource.username=${DB_USER:postgres}
                spring.datasource.password=${DB_PASSWORD:postgres}
                spring.jpa.hibernate.ddl-auto=update
                spring.jpa.show-sql=false
                spring.jpa.open-in-view=false
                ai.service.url=${AI_SERVICE_URL:http://localhost:8000}
                """.formatted(database);
    }

    public static String envExample(String database) {
        return """
                SERVER_PORT=8080
                DB_URL=jdbc:postgresql://localhost:5434/%s
                DB_USER=postgres
                DB_PASSWORD=postgres
                CORS_ALLOWED_ORIGINS=http://localhost:3000
                AI_SERVICE_URL=http://localhost:8000
                """.formatted(database);
    }

    public static String compose(String database) {
        return """
                services:
                  postgres:
                    image: postgres:17
                    environment:
                      POSTGRES_DB: %s
                      POSTGRES_USER: postgres
                      POSTGRES_PASSWORD: postgres
                    ports:
                      - "5434:5432"
                    volumes:
                      - postgres_data:/var/lib/postgresql/data
                volumes:
                  postgres_data:
                """.formatted(database);
    }

    public static String readme(ApplicationSchema schema, String artifact, String database) {
        String endpoints = schema.entities().stream()
                .map(entity -> "- `" + SpringNames.restRoute(entity.technicalName()) + "` — CRUD de " + entity.name())
                .collect(Collectors.joining("\n"));
        return """
                # %s backend

                API REST generada deterministicamente por LogicDraft a partir del diagrama de **%s**. Incluye
                entidades JPA, DTO, repositorios, servicios, controladores, validacion y persistencia PostgreSQL.
                El contrato detallado de cada recurso se encuentra en [`API.md`](API.md).

                ## Requisitos

                - Java 21
                - Maven 3.9+
                - PostgreSQL 17 (local o mediante el `docker-compose.yml` incluido)
                - Docker con Compose es opcional y solo se necesita si deseas iniciar el PostgreSQL incluido

                Este proyecto no incluye Maven Wrapper. Verifica las herramientas con `java -version`, `mvn -version`
                y, si usaras el contenedor incluido, `docker compose version`.

                ## Variables de entorno

                - `SERVER_PORT` (default `8080`)
                - `DB_URL` (default `jdbc:postgresql://localhost:5434/%s`)
                - `DB_USER` (default `postgres`): usuario PostgreSQL. Si tu plataforma llama a esta variable
                  `DB_USERNAME`, asigna su valor a `DB_USER`; el nombre consumido por este proyecto es `DB_USER`.
                - `DB_PASSWORD` (default `postgres`, solo desarrollo)
                - `CORS_ALLOWED_ORIGINS` (lista separada por comas; default `http://localhost:3000`)
                - `AI_SERVICE_URL` (default `http://localhost:8000`): ai-service local de LogicDraft.

                Consulta `.env.example` para ver todos los valores. Es un archivo de referencia: Spring no carga
                `.env` automaticamente. Exporta las variables en la terminal, configuralas en el IDE o usa el
                mecanismo de secretos de tu plataforma. No guardes credenciales reales en Git.

                ## Escenario 1: primera ejecucion de un proyecto nuevo

                La base PostgreSQL debe existir antes de iniciar Spring. Hibernate puede crear o actualizar tablas
                con `ddl-auto=update`, pero no crea la base de datos.

                Opcion recomendada para desarrollo, usando el Docker Compose que realmente incluye este ZIP:

                ```bash
                docker compose up -d
                docker compose ps
                mvn spring-boot:run
                ```

                El contenedor crea `%s` en el puerto host `5434`, con usuario y password `postgres`. Tambien puedes
                crear la base manualmente en tu servidor PostgreSQL y definir `DB_URL`, `DB_USER` y `DB_PASSWORD`
                antes de ejecutar `mvn spring-boot:run`.

                Comprueba la API en `http://localhost:8080%s`. Una respuesta `[]` confirma que el endpoint esta
                disponible y todavia no contiene registros.

                ## Escenario 2: PostgreSQL o proyecto ya configurado

                No inicies el contenedor incluido si ya utilizas otra instancia. Conserva tus datos y configura la
                conexion existente, por ejemplo en PowerShell:

                ```powershell
                $env:DB_URL="jdbc:postgresql://localhost:5432/mi_base_existente"
                $env:DB_USER="mi_usuario"
                $env:DB_PASSWORD="mi_password"
                $env:SERVER_PORT="8080"
                mvn spring-boot:run
                ```

                `ddl-auto=update` intenta adaptar tablas sin borrar la base, pero sigue siendo una comodidad de
                desarrollo. Antes de conectarte a datos importantes crea un respaldo y, para produccion, usa
                migraciones versionadas revisadas por tu equipo.

                ## Estructura del proyecto

                ```text
                src/main/java/com/logicdraft/generated/.../
                  config/       CORS
                  controller/   endpoints REST
                  dto/          contratos CreateRequest, UpdateRequest y Response
                  entity/       entidades JPA
                  error/        respuestas de error
                  repository/   acceso a PostgreSQL
                  service/      logica CRUD y relaciones
                src/main/resources/application.properties
                API.md
                .env.example
                docker-compose.yml
                pom.xml
                ```

                ## Ejecucion y URL base

                Ejecuta `mvn spring-boot:run`. La URL base predeterminada es `http://localhost:8080`; puedes cambiar
                el puerto con `SERVER_PORT`. Abrir `/` puede devolver 404 porque este proyecto es una API: utiliza
                las rutas `/api/...` listadas a continuacion y detalladas en `API.md`.

                ## Resumen de endpoints CRUD

                Cada ruta admite GET de coleccion, GET por ID, POST, PUT y DELETE:

                %s

                ## Conectar un frontend externo y CORS

                Configura el cliente con `http://localhost:8080` como URL del backend. Por ejemplo, Axios puede usar
                `axios.create({ baseURL: 'http://localhost:8080/api' })`; Flutter puede recibir la URL mediante
                `--dart-define=API_BASE_URL=http://localhost:8080`.

                En navegadores, agrega el origen exacto del frontend a `CORS_ALLOWED_ORIGINS`. Para Vite suele ser
                `http://localhost:5173`; para varios origenes usa una lista separada por comas. No uses `*` con
                credenciales. Reinicia Spring despues de cambiar variables de entorno.

                ## Comandos de IA en desarrollo local

                `POST /api/ai/commands` recibe `{ "text": "Registra una categoria llamada Cabello" }`.
                Solo permite CREATE. El backend envia el esquema generado a `AI_SERVICE_URL/api/runtime/interpret`,
                valida la respuesta y usa los servicios y repositorios CRUD para guardar en PostgreSQL. La IA no
                ejecuta SQL ni necesita acceso a la base. Inicia por separado `ai-service` con Ollama disponible y
                `OLLAMA_MODEL` configurado; este ZIP no empaqueta ni despliega ese servicio. Si el servicio no esta
                disponible, los CRUD manuales siguen funcionando.
                Desde el repositorio LogicDraft, entra a `ai-service`, instala `requirements.txt`, configura su `.env`
                y ejecuta `python -m app.main`. El backend debe poder alcanzar la URL indicada por `AI_SERVICE_URL`.
                Para relaciones, escribe el nombre real (por ejemplo `en la categoria Cabello`): Spring busca una
                coincidencia unica; si no existe o hay varias, pedira aclaracion. Tambien puedes indicar un ID
                explicito con sintaxis `categoriaId=7`; los IDs no presentes en el texto se rechazan. Las relaciones
                N:M aceptan una lista de nombres existentes, por ejemplo `Registra un alumno Gabriel y relacionalo
                con Matematicas y Fisica`. Cada nombre debe aparecer en la instruccion y resolverse de forma unica;
                no se inventan IDs ni se crean registros relacionados implicitamente. Crea primero esos registros
                mediante su CRUD. Una referencia ausente o ambigua solicita revision y la transaccion no guarda un
                registro parcial.

                Antes de persistir, Spring vuelve a comprobar todos los campos y relaciones obligatorios del
                `ApplicationSchema`. Una PK generada nunca se solicita. Si falta informacion devuelve HTTP 200 con
                `status: NEEDS_CLARIFICATION`, un mensaje y `missingFields`; no construye el DTO ni llama al servicio
                de creacion. Corrige el comando original y envialo completo otra vez, por ejemplo:

                - Completo: `Registra un personal llamado Jose, edad 23, telefono 78159999`.
                - Incompleto: `Registra un personal llamado Jose, edad 23` (solicita `telefono`).
                - Con relacion: `Registra un corte Degradado de 25 en la categoria Cabello`.

                Esta version no mantiene memoria conversacional en el servidor: la aplicacion Flutter conserva el
                texto para que puedas editarlo y reenviarlo. UPDATE, DELETE y READ mediante IA siguen rechazados.

                ## Relaciones muchos a muchos

                Una N:M simple se genera como `@ManyToMany` unidireccional en un unico lado propietario. Hibernate
                crea una tabla intermedia con dos foreign keys y una restriccion UNIQUE para la pareja. Los DTO usan
                un `Set` de IDs: POST y PUT reemplazan el conjunto de asociaciones, y un conjunto vacio elimina las
                asociaciones sin borrar los registros relacionados. No se configura cascade de borrado.

                Si la relacion necesita atributos propios (por ejemplo fecha o nota), modelala en LogicDraft como
                una entidad asociativa explicita con esos campos y dos relaciones; el generador no inventa columnas.
                Las autorrelaciones y las PK compuestas siguen fuera de alcance.

                Los atributos con nombre explicito de telefono se normalizan solo si el esquema los declara STRING.
                Una secuencia inequivoca como `telefono 781, 543, 23` se guarda como `78154323`, preservando orden y
                ceros iniciales. El valor debe aparecer una unica vez como telefono en la instruccion; si es ambiguo,
                fue inventado por el modelo o el atributo esta modelado como numero, se solicita revision. Esta regla
                no elimina comas de importes, decimales ni otros campos. La obligatoriedad siempre proviene de
                `nullable` en el ApplicationSchema.

                El backend registra el comando (con redaccion basica de secretos), URL sin credenciales, status HTTP,
                cuerpo de error acotado, fallos de conexion, timeout, JSON invalido y rechazo de validacion. Un upstream
                no disponible responde 503, timeout 504 y respuesta HTTP/JSON/contrato invalido 502. Los estados
                `NEEDS_CLARIFICATION` y `NOT_UNDERSTOOD` son respuestas normales con HTTP 200.

                ## Solución de problemas

                - Inicia PostgreSQL con `docker compose up -d` y comprueba su estado con `docker compose ps`.
                - Si aparece `password authentication failed`, verifica que `DB_URL`, `DB_USER` y `DB_PASSWORD`
                  coincidan con `docker-compose.yml`. Si el volumen contiene credenciales anteriores y sus datos
                  pueden descartarse, ejecuta `docker compose down -v` y luego `docker compose up -d`.
                - Si el puerto PostgreSQL `5434` esta ocupado, cambia el lado izquierdo de `"5434:5432"` en
                  `docker-compose.yml` y usa el mismo puerto de host en `DB_URL`. El puerto interno sigue siendo `5432`.
                - Si el puerto HTTP `8080` esta ocupado, define otro valor en `SERVER_PORT` antes de iniciar Spring.
                  El error `Web server failed to start. Port 8080 was already in use.` se resuelve, por ejemplo, asi:

                  PowerShell:

                  ```powershell
                  $env:SERVER_PORT="8082"
                  mvn spring-boot:run
                  # Alternativa en una linea:
                  $env:SERVER_PORT="8082"; mvn spring-boot:run
                  ```

                  CMD:

                  ```cmd
                  set SERVER_PORT=8082
                  mvn spring-boot:run
                  ```

                  Si usas el frontend Flutter generado, ejecutalo con
                  `--dart-define=API_BASE_URL=http://localhost:8082`. `SERVER_PORT` y `API_BASE_URL` deben coincidir.
                - Abrir `/` no representa necesariamente un error: este proyecto es una API REST. Usa las rutas
                  `/api/...` documentadas arriba.
                - Una respuesta `[]` en un GET de coleccion significa que el endpoint funciona y aun no hay registros.
                - `.env.example` es solo una referencia; Spring no carga archivos `.env` automaticamente. Exporta las
                  variables en tu entorno o configuralas desde tu IDE o terminal.
                - `spring.jpa.hibernate.ddl-auto=update` es solo para desarrollo. En produccion utiliza migraciones
                  versionadas.
                """.formatted(schema.projectName(), schema.projectName(), database, database,
                    SpringNames.restRoute(schema.entities().get(0).technicalName()), endpoints);
    }
}
