package com.sw1.backend.generator;

import com.sw1.backend.generator.flutter.FlutterGenerator;
import com.sw1.backend.generator.schema.ApplicationSchema;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

class GeneratedFlutterAnalysisTest {
    @TempDir Path temporaryDirectory;

    @Test
    void generatedFlutterProjectPassesRealAnalysisWhenEnabled() throws Exception {
        String flutter = System.getenv("FLUTTER_TEST_EXECUTABLE");
        Assumptions.assumeTrue(flutter != null && !flutter.isBlank(),
                "Define FLUTTER_TEST_EXECUTABLE para habilitar la validacion real opcional");
        analyze(flutter, GeneratorTestSchemas.associativeMetadataSchema(),
                temporaryDirectory.resolve("generated-associative-app"));
    }

    private void analyze(String flutter, ApplicationSchema schema, Path directory) throws Exception {
        var project = new FlutterGenerator().generate(schema);
        String generatedWidgetTest = project.files().get("test/widget_test.dart");
        String generatedVoiceTest = project.files().get("test/runtime_command_test.dart");
        for (var file : project.files().entrySet()) {
            Path target = directory.resolve(file.getKey());
            Files.createDirectories(target.getParent());
            Files.writeString(target, file.getValue());
        }
        run(directory, flutter, "create", "--platforms=android,windows", ".");
        assertEquals(generatedWidgetTest, Files.readString(directory.resolve("test/widget_test.dart")),
                "flutter create no debe sobrescribir el smoke test generado por LogicDraft");
        assertEquals(generatedVoiceTest, Files.readString(directory.resolve("test/runtime_command_test.dart")),
                "flutter create no debe sobrescribir las pruebas de voz generadas por LogicDraft");
        run(directory, flutter, "pub", "get");
        run(directory, flutter, "analyze", "--no-fatal-infos");
        run(directory, flutter, "test");
        run(directory, flutter, "build", "windows");
    }

    private void run(Path directory, String executable, String... arguments) throws Exception {
        String[] command = new String[arguments.length + 1];
        command[0] = executable;
        System.arraycopy(arguments, 0, command, 1, arguments.length);
        Path outputFile = Files.createTempFile(directory, "flutter-", ".log");
        Process process = new ProcessBuilder(command).directory(directory.toFile())
                .redirectErrorStream(true).redirectOutput(outputFile.toFile()).start();
        boolean completed = process.waitFor(Duration.ofMinutes(5).toMillis(), TimeUnit.MILLISECONDS);
        if (!completed) {
            process.destroyForcibly();
            fail("Flutter excedio el tiempo limite durante " + String.join(" ", arguments));
        }
        String output = Files.readString(outputFile);
        assertEquals(0, process.exitValue(), () -> "Flutter fallo durante " + String.join(" ", arguments) + ":\n" + output);
    }
}
