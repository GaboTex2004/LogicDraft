package com.sw1.backend.generator.flutter;

import com.sw1.backend.generator.schema.CanonicalType;

public final class FlutterTypeMapper {
    private FlutterTypeMapper() {
    }

    public static String dartType(CanonicalType type) {
        return switch (type) {
            case STRING -> "String";
            case INTEGER, LONG -> "int";
            case DECIMAL -> "double";
            case BOOLEAN -> "bool";
            case DATE, DATETIME -> "DateTime";
        };
    }
}
