package com.sw1.backend.generator.export;

import com.sw1.backend.generator.flutter.FlutterGenerator;
import com.sw1.backend.generator.schema.ApplicationSchema;
import com.sw1.backend.generator.spring.SpringBootGenerator;
import com.sw1.backend.generator.spring.SpringNames;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class FullStackGenerator {
    private final SpringBootGenerator spring;
    private final FlutterGenerator flutter;

    public FullStackGenerator(SpringBootGenerator spring, FlutterGenerator flutter) {
        this.spring = spring;
        this.flutter = flutter;
    }

    public FullStackProject generate(ApplicationSchema schema) {
        var backend = spring.generate(schema);
        var frontend = flutter.generate(schema);
        String root = SpringNames.artifactName(schema.technicalName()).replaceFirst("-backend$", "");
        Map<String, String> files = new LinkedHashMap<>();
        backend.files().forEach((path, content) -> files.put("backend/" + path, content));
        frontend.files().forEach((path, content) -> files.put("frontend/" + path, content));
        files.put("README.md", readme(schema));
        return new FullStackProject(root, root + ".zip", files);
    }

    private static String readme(ApplicationSchema schema) {
        return """
                # %s

                Proyecto fullstack generado deterministicamente por LogicDraft:

                - `backend/`: API REST Java 21 + Spring Boot 4.1.1 + PostgreSQL 17.
                - `frontend/`: aplicacion Flutter para Android y Windows.

                ## Inicio rapido

                1. `cd backend`
                2. `docker compose up -d`
                3. `mvn spring-boot:run`
                4. `cd ../frontend`
                5. `flutter create --platforms=android,windows .`
                6. `flutter pub get`
                7. `flutter run`

                El backend permite cambiar `SERVER_PORT`, `DB_URL`, `DB_USER` y `DB_PASSWORD`. Flutter recibe la URL
                con `--dart-define=API_BASE_URL=http://localhost:8080`.

                Para comandos de IA runtime (solo CREATE), inicia aparte el `ai-service` de LogicDraft y Ollama.
                El backend generado usa `AI_SERVICE_URL=http://localhost:8000` por defecto; el ZIP no empaqueta
                ese servicio. El CRUD manual funciona aunque la IA no este disponible.

                Las relaciones N:M simples comparten el mismo contrato en ambos proyectos: Spring usa una unica
                tabla intermedia con foreign keys y pareja UNIQUE, y Flutter ofrece seleccion multiple de registros
                existentes. Para relaciones con atributos propios se genera una entidad asociativa explicita.

                Ambos puertos deben coincidir. Por ejemplo, en Windows:

                ```powershell
                $env:SERVER_PORT="8082"
                mvn spring-boot:run
                flutter run -d windows --dart-define=API_BASE_URL=http://localhost:8082
                ```

                ## Problemas frecuentes

                ### Puerto 8080 ocupado

                Si Spring muestra `Web server failed to start. Port 8080 was already in use.`, en PowerShell usa:

                ```powershell
                $env:SERVER_PORT="8082"
                mvn spring-boot:run
                ```

                Despues inicia Flutter Windows con:

                ```powershell
                flutter run -d windows --dart-define=API_BASE_URL=http://localhost:8082
                ```

                `SERVER_PORT` define el puerto HTTP de Spring y `API_BASE_URL` indica a Flutter donde encontrarlo;
                ambos tienen que coincidir.

                Consulta `backend/README.md` para PostgreSQL y `frontend/README.md` para Windows, Android fisico,
                IP local y `adb reverse`.
                """.formatted(schema.projectName());
    }
}
