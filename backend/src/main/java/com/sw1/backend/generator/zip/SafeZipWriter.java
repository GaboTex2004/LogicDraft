package com.sw1.backend.generator.zip;

import com.sw1.backend.generator.spring.SpringGeneratorException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class SafeZipWriter {
    private SafeZipWriter() {
    }

    public static byte[] write(ZipProject project) {
        validateSegment(project.rootDirectoryName(), "directorio raiz");
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
             ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            for (var file : new TreeMap<>(project.files()).entrySet()) {
                String relative = validateRelativePath(file.getKey());
                ZipEntry entry = new ZipEntry(project.rootDirectoryName() + "/" + relative);
                entry.setTime(0L);
                zip.putNextEntry(entry);
                zip.write(file.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
            zip.finish();
            return bytes.toByteArray();
        } catch (IOException exception) {
            throw new SpringGeneratorException("No se pudo construir el ZIP generado.");
        }
    }

    private static String validateRelativePath(String value) {
        if (value == null || value.isBlank() || value.indexOf('\\') >= 0 || value.startsWith("/")
                || value.matches("^[A-Za-z]:.*")) {
            throw new SpringGeneratorException("El generador produjo una ruta de archivo insegura.");
        }
        Path normalized = Path.of(value).normalize();
        if (normalized.isAbsolute() || normalized.startsWith("..") || !normalized.toString().replace('\\', '/').equals(value)) {
            throw new SpringGeneratorException("El generador produjo una ruta de archivo insegura: " + value);
        }
        return value;
    }

    private static void validateSegment(String value, String subject) {
        if (value == null || !value.matches("[a-z0-9][a-z0-9-]*")) {
            throw new SpringGeneratorException("El " + subject + " del ZIP no es seguro.");
        }
    }
}
