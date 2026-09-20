package com.sw1.backend.generator.spring;

import com.sw1.backend.generator.schema.CanonicalType;

public final class SpringTypeMapper {
    public record TypeMapping(String javaType, String javaImport, String postgresType) {}

    private SpringTypeMapper() {
    }

    public static TypeMapping map(CanonicalType type) {
        if (type == null) throw new SpringGeneratorException("El tipo canonico no puede ser null.");
        return switch (type) {
            case STRING -> new TypeMapping("String", null, "VARCHAR");
            case INTEGER -> new TypeMapping("Integer", null, "INTEGER");
            case LONG -> new TypeMapping("Long", null, "BIGINT");
            case DECIMAL -> new TypeMapping("BigDecimal", "java.math.BigDecimal", "NUMERIC");
            case BOOLEAN -> new TypeMapping("Boolean", null, "BOOLEAN");
            case DATE -> new TypeMapping("LocalDate", "java.time.LocalDate", "DATE");
            case DATETIME -> new TypeMapping("LocalDateTime", "java.time.LocalDateTime", "TIMESTAMP");
        };
    }
}
