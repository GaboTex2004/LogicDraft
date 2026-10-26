package com.sw1.backend.generator.export;

import com.sw1.backend.generator.naming.TechnicalNameNormalizer;
import com.sw1.backend.generator.schema.UmlStructuralModel;
import com.sw1.backend.generator.validation.ApplicationSchemaException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Generates explicit, non-persistent domain skeletons without inventing method implementations. */
public final class UmlStructuralGenerator {
    private static final Set<String> PRIMITIVES = Set.of("VARCHAR", "TEXT", "STRING", "INTEGER", "INT",
            "BIGINT", "LONG", "DECIMAL", "DOUBLE", "FLOAT", "BOOLEAN", "BOOL", "DATE", "TIMESTAMP",
            "DATETIME", "VOID");

    private UmlStructuralGenerator() {}

    public static Map<String, String> javaFiles(String basePackage, UmlStructuralModel model) {
        validate(model);
        Map<String, String> result = new LinkedHashMap<>();
        Map<String, UmlStructuralModel.Classifier> byId = byId(model);
        for (UmlStructuralModel.Classifier item : model.classifiers()) {
            String name = typeName(item.name());
            result.put("src/main/java/" + basePackage.replace('.', '/') + "/domain/uml/" + name + ".java",
                    renderJava(basePackage + ".domain.uml", item, model, byId));
        }
        return result;
    }

    public static Map<String, String> dartFiles(UmlStructuralModel model) {
        validate(model);
        Map<String, String> result = new LinkedHashMap<>();
        Map<String, UmlStructuralModel.Classifier> byId = byId(model);
        for (UmlStructuralModel.Classifier item : model.classifiers()) {
            String name = typeName(item.name());
            String file = TechnicalNameNormalizer.fieldName(name).replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase();
            result.put("lib/models/uml/" + file + ".dart", renderDart(item, model, byId));
        }
        return result;
    }

    private static String renderJava(String pkg, UmlStructuralModel.Classifier item, UmlStructuralModel model,
                                     Map<String, UmlStructuralModel.Classifier> byId) {
        String name = typeName(item.name());
        if ("ENUMERATION".equals(item.kind())) {
            return "package " + pkg + ";\n\npublic enum " + name + " {\n    "
                    + String.join(",\n    ", item.literals().stream().map(UmlStructuralGenerator::enumLiteral).toList())
                    + "\n}\n";
        }
        List<String> parents = parents(item.id(), model, byId, "GENERALIZATION");
        List<String> contracts = parents(item.id(), model, byId, "REALIZATION");
        StringBuilder out = new StringBuilder("package ").append(pkg).append(";\n\n");
        if ("INTERFACE".equals(item.kind())) {
            out.append("public interface ").append(name);
            if (!parents.isEmpty()) out.append(" extends ").append(String.join(", ", parents));
            out.append(" {\n");
            for (var operation : item.operations()) out.append(javaOperation(operation, true));
        } else {
            out.append("public ");
            if (item.abstractType() || !contracts.isEmpty()
                    || item.operations().stream().anyMatch(UmlStructuralModel.Operation::abstractMember)) out.append("abstract ");
            out.append("class ").append(name);
            if (!parents.isEmpty()) out.append(" extends ").append(parents.getFirst());
            if (!contracts.isEmpty()) out.append(" implements ").append(String.join(", ", contracts));
            out.append(" {\n");
            for (var attribute : item.attributes()) {
                out.append("    ").append(javaVisibility(attribute.visibility()));
                if (attribute.staticMember()) out.append("static ");
                out.append(javaType(attribute.type())).append(' ').append(fieldName(attribute.name())).append(";\n");
            }
            if (!item.attributes().isEmpty() && !item.operations().isEmpty()) out.append('\n');
            for (var operation : item.operations()) out.append(javaOperation(operation, false));
        }
        return out.append("}\n").toString();
    }

    private static String javaOperation(UmlStructuralModel.Operation operation, boolean ownerInterface) {
        StringBuilder out = new StringBuilder("    ").append(javaVisibility(operation.visibility()));
        if (operation.staticMember()) out.append("static ");
        if (operation.abstractMember() && !ownerInterface && !operation.staticMember()) out.append("abstract ");
        out.append(javaType(operation.returnType())).append(' ').append(fieldName(operation.name())).append('(');
        List<String> parameters = operation.parameters().stream()
                .map(value -> javaType(value.type()) + " " + fieldName(value.name())).toList();
        out.append(String.join(", ", parameters)).append(')');
        if (ownerInterface && !operation.staticMember() || operation.abstractMember() && !operation.staticMember()) {
            return out.append(";\n").toString();
        }
        return out.append(" {\n        throw new UnsupportedOperationException(\"Lógica UML pendiente\");\n    }\n").toString();
    }

    private static String renderDart(UmlStructuralModel.Classifier item, UmlStructuralModel model,
                                     Map<String, UmlStructuralModel.Classifier> byId) {
        String name = typeName(item.name());
        if ("ENUMERATION".equals(item.kind())) {
            return "enum " + name + " {\n  "
                    + String.join(",\n  ", item.literals().stream().map(UmlStructuralGenerator::dartEnumLiteral).toList())
                    + "\n}\n";
        }
        List<String> parents = parents(item.id(), model, byId, "GENERALIZATION");
        List<String> contracts = parents(item.id(), model, byId, "REALIZATION");
        StringBuilder out = new StringBuilder();
        if ("INTERFACE".equals(item.kind()) || item.abstractType() || !contracts.isEmpty()
                || item.operations().stream().anyMatch(UmlStructuralModel.Operation::abstractMember)) out.append("abstract ");
        out.append("class ").append(name);
        if (!parents.isEmpty()) out.append(" extends ").append(parents.getFirst());
        if (!contracts.isEmpty()) out.append(" implements ").append(String.join(", ", contracts));
        out.append(" {\n");
        for (var attribute : item.attributes()) {
            out.append("  ");
            if (attribute.staticMember()) out.append("static ");
            out.append("late ").append(dartType(attribute.type())).append(' ').append(fieldName(attribute.name())).append(";\n");
        }
        for (var operation : item.operations()) {
            out.append("  ");
            if (operation.staticMember()) out.append("static ");
            out.append(dartType(operation.returnType())).append(' ').append(fieldName(operation.name())).append('(')
                    .append(String.join(", ", operation.parameters().stream()
                            .map(value -> dartType(value.type()) + " " + fieldName(value.name())).toList()))
                    .append(") => throw UnimplementedError('Lógica UML pendiente');\n");
        }
        return out.append("}\n").toString();
    }

    private static void validate(UmlStructuralModel model) {
        if (model == null) return;
        Map<String, UmlStructuralModel.Classifier> byId = byId(model);
        Map<String, String> names = new HashMap<>();
        for (var item : model.classifiers()) {
            String technical = typeName(item.name());
            if (names.putIfAbsent(technical.toLowerCase(), item.name()) != null)
                throw new ApplicationSchemaException("Dos elementos UML producen el mismo nombre técnico: " + technical + ".");
            if ("ENUMERATION".equals(item.kind()) && item.literals().isEmpty())
                throw new ApplicationSchemaException("La enumeración " + item.name() + " no contiene literales generables.");
            for (var member : item.attributes()) requireType(item.name(), member.name(), member.type(), model);
            for (var operation : item.operations()) {
                requireType(item.name(), operation.name(), operation.returnType(), model);
                for (var parameter : operation.parameters()) requireType(item.name(), parameter.name(), parameter.type(), model);
            }
        }
        for (var relation : model.relations()) {
            if (!byId.containsKey(relation.sourceId()) || !byId.containsKey(relation.targetId()))
                throw new ApplicationSchemaException("La relación UML " + relation.id() + " referencia un tipo no generable.");
        }
    }

    private static void requireType(String owner, String member, String raw, UmlStructuralModel model) {
        boolean known = PRIMITIVES.contains(raw.toUpperCase())
                || model.classifiers().stream().anyMatch(item -> item.name().equalsIgnoreCase(raw));
        if (!known) throw new ApplicationSchemaException("El miembro " + owner + "." + member
                + " referencia el tipo UML no generable '" + raw + "'.");
    }

    private static List<String> parents(String source, UmlStructuralModel model,
                                        Map<String, UmlStructuralModel.Classifier> byId, String kind) {
        List<String> result = new ArrayList<>();
        for (var relation : model.relations()) if (kind.equals(relation.kind()) && source.equals(relation.sourceId()))
            result.add(typeName(byId.get(relation.targetId()).name()));
        if ("GENERALIZATION".equals(kind) && result.size() > 1)
            throw new ApplicationSchemaException("El elemento UML " + source + " tiene herencia múltiple no generable en Java.");
        return result;
    }

    private static Map<String, UmlStructuralModel.Classifier> byId(UmlStructuralModel model) {
        Map<String, UmlStructuralModel.Classifier> result = new LinkedHashMap<>();
        if (model != null) for (var item : model.classifiers()) result.put(item.id(), item);
        return result;
    }

    private static String javaType(String raw) { return switch (raw.toUpperCase()) {
        case "VARCHAR", "TEXT", "STRING", "CHAR" -> "String"; case "INTEGER", "INT", "SHORT" -> "Integer";
        case "BIGINT", "LONG" -> "Long"; case "DECIMAL", "DOUBLE", "FLOAT", "REAL", "NUMERIC" -> "Double";
        case "BOOLEAN", "BOOL" -> "Boolean"; case "DATE" -> "java.time.LocalDate";
        case "TIMESTAMP", "DATETIME" -> "java.time.LocalDateTime"; case "VOID" -> "void"; default -> typeName(raw); }; }
    private static String dartType(String raw) { return switch (raw.toUpperCase()) {
        case "VARCHAR", "TEXT", "STRING", "CHAR" -> "String"; case "INTEGER", "INT", "SHORT", "BIGINT", "LONG" -> "int";
        case "DECIMAL", "DOUBLE", "FLOAT", "REAL", "NUMERIC" -> "double"; case "BOOLEAN", "BOOL" -> "bool";
        case "DATE", "TIMESTAMP", "DATETIME" -> "DateTime"; case "VOID" -> "void"; default -> typeName(raw); }; }
    private static String javaVisibility(String raw) { return switch (raw) { case "public" -> "public "; case "protected" -> "protected "; case "private" -> "private "; default -> ""; }; }
    private static String typeName(String raw) { return TechnicalNameNormalizer.typeName(raw); }
    private static String fieldName(String raw) { return TechnicalNameNormalizer.fieldName(raw); }
    private static String enumLiteral(String raw) { return TechnicalNameNormalizer.typeName(raw).replaceAll("([a-z0-9])([A-Z])", "$1_$2").toUpperCase(); }
    private static String dartEnumLiteral(String raw) { String value = fieldName(raw); return Character.toLowerCase(value.charAt(0)) + value.substring(1); }
}
