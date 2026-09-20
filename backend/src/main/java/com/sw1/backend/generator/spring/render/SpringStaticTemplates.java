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

                Backend generado deterministicamente por LogicDraft para Java 21, Spring Boot y PostgreSQL.

                ## Requisitos

                - Java 21
                - Maven 3.9+
                - PostgreSQL 17 (o una version compatible)

                Inicia PostgreSQL de desarrollo con `docker compose up -d`, o configura:

                - `SERVER_PORT` (default `8080`)
                - `DB_URL` (default `jdbc:postgresql://localhost:5434/%s`)
                - `DB_USER` (default `postgres`)
                - `DB_PASSWORD` (default `postgres`, solo desarrollo)
                - `CORS_ALLOWED_ORIGINS` (lista separada por comas; default `http://localhost:3000`)
                - `AI_SERVICE_URL` (default `http://localhost:8000`): ai-service local de LogicDraft.

                `.env.example` es solo una referencia: Spring no carga archivos `.env` automaticamente.

                Ejecuta con `mvn spring-boot:run`. `ddl-auto=update` simplifica desarrollo; produccion debe usar migraciones versionadas.

                ## Endpoints

                Cada ruta admite GET de coleccion, GET por ID, POST, PUT y DELETE:

                %s

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
                """.formatted(schema.projectName(), database, endpoints);
    }
}
