package com.sw1.backend.generator.schema;

public record ApplicationField(
        String id,
        String name,
        String technicalName,
        CanonicalType type,
        boolean primaryKey,
        boolean nullable,
        boolean generated) {
}
