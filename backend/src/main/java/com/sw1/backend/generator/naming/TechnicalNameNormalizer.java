package com.sw1.backend.generator.naming;

import com.sw1.backend.generator.validation.ApplicationSchemaException;
import java.text.Normalizer;
import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

public final class TechnicalNameNormalizer {
    private TechnicalNameNormalizer() {
    }

    public static String typeName(String displayName) {
        String pascal = pascalCase(displayName);
        return Character.isDigit(pascal.charAt(0)) ? "N" + pascal : pascal;
    }

    public static String fieldName(String displayName) {
        String pascal = pascalCase(displayName);
        String result = Character.toLowerCase(pascal.charAt(0)) + pascal.substring(1);
        return Character.isDigit(result.charAt(0)) ? "n" + result : result;
    }

    private static String pascalCase(String displayName) {
        if (displayName == null || displayName.isBlank()) {
            throw new ApplicationSchemaException("El nombre no puede estar vacio.");
        }
        String decomposed = Normalizer.normalize(displayName.strip(), Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .replaceAll("([a-z0-9])([A-Z])", "$1 $2")
                .replaceAll("[^A-Za-z0-9]+", " ")
                .strip();
        if (decomposed.isEmpty()) {
            throw new ApplicationSchemaException("El nombre '" + displayName + "' no produce un identificador tecnico valido.");
        }
        return Arrays.stream(decomposed.split(" +"))
                .map(word -> word.substring(0, 1).toUpperCase(Locale.ROOT)
                        + word.substring(1).toLowerCase(Locale.ROOT))
                .collect(Collectors.joining());
    }
}
