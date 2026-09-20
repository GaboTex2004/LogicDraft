package com.sw1.backend.generator.flutter;

import com.sw1.backend.generator.flutter.render.DartModelRenderer;
import com.sw1.backend.generator.flutter.render.DartScreenRenderer;
import com.sw1.backend.generator.flutter.render.DartServiceRenderer;
import com.sw1.backend.generator.flutter.render.FlutterStaticTemplates;
import com.sw1.backend.generator.schema.ApplicationEntity;
import com.sw1.backend.generator.schema.ApplicationSchema;
import com.sw1.backend.generator.spring.SpringGenerationValidator;
import com.sw1.backend.generator.spring.SpringNames;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class FlutterGenerator {
    public FlutterProject generate(ApplicationSchema schema) {
        var relations = FlutterContract.relations(schema);
        SpringGenerationValidator.validate(schema, relations);
        String packageName = SpringNames.sqlName(schema.technicalName(), "paquete Flutter");
        Map<String, String> files = new LinkedHashMap<>();
        files.put("pubspec.yaml", FlutterStaticTemplates.pubspec(packageName));
        files.put("analysis_options.yaml", FlutterStaticTemplates.analysisOptions());
        files.put("README.md", FlutterStaticTemplates.readme(schema));
        files.put("lib/main.dart", FlutterStaticTemplates.main(packageName));
        files.put("test/widget_test.dart", FlutterStaticTemplates.widgetTest(packageName, schema.projectName()));
        files.put("test/runtime_command_test.dart", FlutterStaticTemplates.runtimeCommandTest(packageName));
        files.put("assets/voice/README.txt", FlutterStaticTemplates.voiceModelReadme());
        files.put("lib/core/config/api_config.dart", FlutterStaticTemplates.apiConfig());
        files.put("lib/core/network/api_client.dart", FlutterStaticTemplates.apiClient());
        files.put("lib/services/runtime_ai_command_service.dart", FlutterStaticTemplates.runtimeAiCommandService());
        files.put("lib/services/voice_input_service.dart", FlutterStaticTemplates.voiceInputService());
        files.put("lib/screens/home_screen.dart", FlutterStaticTemplates.home(schema));
        files.put("lib/screens/runtime_command.dart", FlutterStaticTemplates.runtimeCommand());
        files.put("android/README.md", FlutterStaticTemplates.platformNotice("Android"));
        files.put("windows/README.md", FlutterStaticTemplates.platformNotice("Windows"));
        for (ApplicationEntity entity : schema.entities()) {
            String fileName = SpringNames.sqlName(entity.technicalName(), "archivo Flutter");
            var owned = relations.get(entity.id());
            files.put("lib/models/" + fileName + ".dart", DartModelRenderer.render(entity, owned));
            files.put("lib/services/" + fileName + "_service.dart", DartServiceRenderer.render(entity, fileName));
            files.put("lib/screens/" + fileName + "/" + fileName + "_list_screen.dart",
                    DartScreenRenderer.list(entity, owned, fileName));
            files.put("lib/screens/" + fileName + "/" + fileName + "_form_screen.dart",
                    DartScreenRenderer.form(entity, owned, fileName));
        }
        return new FlutterProject(packageName, files);
    }
}
