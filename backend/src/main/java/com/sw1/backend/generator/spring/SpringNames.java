package com.sw1.backend.generator.spring;

import java.util.Locale;
import java.util.Set;

public final class SpringNames {
    private static final Set<String> JAVA_RESERVED = Set.of(
            "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class",
            "const", "continue", "default", "do", "double", "else", "enum", "extends", "final",
            "finally", "float", "for", "goto", "if", "implements", "import", "instanceof", "int",
            "interface", "long", "native", "new", "package", "private", "protected", "public",
            "return", "short", "static", "strictfp", "super", "switch", "synchronized", "this",
            "throw", "throws", "transient", "try", "void", "volatile", "while", "record", "sealed",
            "permits", "yield", "var", "true", "false", "null");
    private static final Set<String> POSTGRES_RESERVED = Set.of(
            "all", "analyse", "analyze", "and", "any", "array", "as", "asc", "asymmetric", "authorization",
            "binary", "both", "case", "cast", "check", "collate", "collation", "column", "concurrently",
            "constraint", "create", "cross", "current_catalog", "current_date", "current_role", "current_schema",
            "current_time", "current_timestamp", "current_user", "default", "deferrable", "desc", "distinct",
            "do", "else", "end", "except", "false", "fetch", "for", "foreign", "freeze", "from", "full",
            "grant", "group", "having", "ilike", "in", "initially", "inner", "intersect", "into", "is",
            "isnull", "join", "lateral", "leading", "left", "like", "limit", "localtime", "localtimestamp",
            "natural", "new", "not", "notnull", "null", "offset", "old", "on", "only", "or", "order",
            "outer", "overlaps", "placing", "primary", "references", "returning", "right", "select", "session_user",
            "similar", "some", "symmetric", "table", "tablesample", "then", "to", "trailing", "true", "union",
            "unique", "user", "using", "variadic", "verbose", "when", "where", "window", "with");

    private SpringNames() {
    }

    public static String basePackage(String technicalName) {
        validateJavaIdentifier(technicalName, "nombre de aplicacion");
        return "com.logicdraft.generated." + technicalName.toLowerCase(Locale.ROOT);
    }

    public static String artifactName(String technicalName) {
        return technicalName.toLowerCase(Locale.ROOT) + "-backend";
    }

    public static String databaseName(String technicalName) {
        return snakeCase(technicalName, "base de datos") + "_db";
    }

    public static String restRoute(String entityTechnicalName) {
        return "/api/" + sqlName(entityTechnicalName, "ruta REST");
    }

    public static String sqlName(String technicalName, String subject) {
        String result = snakeCase(technicalName, subject);
        if (result.length() > 63) {
            throw new SpringGeneratorException("El nombre PostgreSQL '" + result + "' de " + subject
                    + " supera el limite de 63 caracteres.");
        }
        if (POSTGRES_RESERVED.contains(result)) {
            throw new SpringGeneratorException("El nombre PostgreSQL '" + result + "' de " + subject + " es una palabra reservada.");
        }
        return result;
    }

    public static String lowerFirst(String typeName) {
        return Character.toLowerCase(typeName.charAt(0)) + typeName.substring(1);
    }

    public static void validateJavaIdentifier(String value, String subject) {
        if (value == null || !value.matches("[A-Za-z_$][A-Za-z0-9_$]*")) {
            throw new SpringGeneratorException("El identificador Java de " + subject + " no es valido.");
        }
        if (JAVA_RESERVED.contains(value.toLowerCase(Locale.ROOT))) {
            throw new SpringGeneratorException("El identificador Java '" + value + "' de " + subject + " es una palabra reservada.");
        }
    }

    private static String snakeCase(String value, String subject) {
        validateJavaIdentifier(value, subject);
        return value.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
    }
}
