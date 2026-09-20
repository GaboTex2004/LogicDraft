package com.sw1.backend.generator;

import com.sw1.backend.generator.spring.GeneratedProject;
import com.sw1.backend.generator.spring.SpringBootGenerator;
import com.sw1.backend.generator.spring.SpringGeneratorException;
import com.sw1.backend.generator.zip.SafeZipWriter;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SafeZipWriterTest {
    @Test
    void zipContainsOnlyFilesUnderExpectedRootAndIsReproducible() throws Exception {
        GeneratedProject project = new SpringBootGenerator().generate(GeneratorTestSchemas.serviceSchema());
        byte[] first = SafeZipWriter.write(project);
        byte[] second = SafeZipWriter.write(project);
        assertArrayEquals(first, second);

        Set<String> entries = new HashSet<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(first), StandardCharsets.UTF_8)) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                entries.add(entry.getName());
                assertTrue(entry.getName().startsWith("servicios-backend/"));
                assertFalse(entry.getName().contains("../"));
                assertFalse(entry.getName().startsWith("/"));
            }
        }
        assertTrue(entries.contains("servicios-backend/pom.xml"));
    }

    @Test
    void rejectsTraversalAbsoluteAndUnexpectedPaths() {
        for (String path : List.of("../secret", "/absolute", "C:/windows", "folder\\file")) {
            GeneratedProject project = new GeneratedProject("safe-root", "safe.zip", Map.of(path, "x"));
            assertThrows(SpringGeneratorException.class, () -> SafeZipWriter.write(project));
        }
    }
}
