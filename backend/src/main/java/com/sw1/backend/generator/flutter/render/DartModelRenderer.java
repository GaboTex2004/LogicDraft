package com.sw1.backend.generator.flutter.render;

import com.sw1.backend.generator.flutter.FlutterContract;
import com.sw1.backend.generator.flutter.FlutterContract.Field;
import com.sw1.backend.generator.schema.ApplicationEntity;
import com.sw1.backend.generator.schema.CanonicalType;
import com.sw1.backend.generator.spring.OwnedRelation;
import java.util.List;

public final class DartModelRenderer {
    private DartModelRenderer() {
    }

    public static String render(ApplicationEntity entity, List<OwnedRelation> relations) {
        List<Field> response = FlutterContract.responseFields(entity, relations);
        List<Field> create = FlutterContract.createFields(entity, relations);
        List<Field> update = FlutterContract.updateFields(entity, relations);
        StringBuilder out = new StringBuilder("class ").append(entity.technicalName()).append(" {\n");
        for (Field field : response) out.append("  final ").append(field.dartType()).append(' ').append(field.name()).append(";\n");
        out.append("\n  const ").append(entity.technicalName()).append("({\n");
        for (Field field : response) {
            out.append("    ").append(field.nullable() ? "" : "required ").append("this.").append(field.name()).append(",\n");
        }
        out.append("  });\n\n  factory ").append(entity.technicalName())
                .append(".fromJson(Map<String, dynamic> json) => ").append(entity.technicalName()).append("(\n");
        for (Field field : response) out.append("    ").append(field.name()).append(": ").append(fromJson(field)).append(",\n");
        out.append("  );\n\n  Map<String, dynamic> toJson() => {\n");
        for (Field field : response) out.append("    '").append(field.name()).append("': ").append(toJson(field)).append(",\n");
        out.append("  };\n\n  Map<String, dynamic> toCreateJson() => {\n");
        for (Field field : create) out.append("    '").append(field.name()).append("': ").append(toJson(field)).append(",\n");
        out.append("  };\n\n  Map<String, dynamic> toUpdateJson() => {\n");
        for (Field field : update) out.append("    '").append(field.name()).append("': ").append(toJson(field)).append(",\n");
        return out.append("  };\n}\n").toString();
    }

    private static String fromJson(Field field) {
        String value = "json['" + field.name() + "']";
        if (field.collection()) {
            String item = itemConversion(field.type(), "item");
            String expression = "(" + value + " as List<dynamic>).map((item) => " + item + ").toList()";
            return field.nullable() ? value + " == null ? null : " + expression : expression;
        }
        String conversion = switch (field.type()) {
            case STRING -> value + " as String";
            case INTEGER, LONG -> "(" + value + " as num).toInt()";
            case DECIMAL -> "(" + value + " as num).toDouble()";
            case BOOLEAN -> value + " as bool";
            case DATE, DATETIME -> "DateTime.parse(" + value + " as String)";
        };
        if (!field.nullable()) return conversion;
        return value + " == null ? null : " + conversion;
    }

    private static String itemConversion(CanonicalType type, String value) {
        return switch (type) {
            case STRING -> value + " as String";
            case INTEGER, LONG -> "(" + value + " as num).toInt()";
            case DECIMAL -> "(" + value + " as num).toDouble()";
            case BOOLEAN -> value + " as bool";
            case DATE, DATETIME -> "DateTime.parse(" + value + " as String)";
        };
    }

    private static String toJson(Field field) {
        if (field.collection() && (field.type() == CanonicalType.DATE || field.type() == CanonicalType.DATETIME)) {
            return field.nullable()
                    ? field.name() + "?.map((item) => item.toIso8601String()).toList()"
                    : field.name() + ".map((item) => item.toIso8601String()).toList()";
        }
        if (field.type() != CanonicalType.DATE && field.type() != CanonicalType.DATETIME) return field.name();
        return field.nullable() ? field.name() + "?.toIso8601String()" : field.name() + ".toIso8601String()";
    }
}
