package com.sw1.backend.generator.spring.render;

import com.sw1.backend.generator.schema.ApplicationEntity;
import com.sw1.backend.generator.schema.ApplicationField;
import com.sw1.backend.generator.schema.ApplicationSchema;
import com.sw1.backend.generator.schema.CanonicalType;
import com.sw1.backend.generator.spring.OwnedRelation;
import com.sw1.backend.generator.spring.SpringNames;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Generates human-readable API documentation from the same schema used by the Java renderers. */
public final class ApiDocumentationRenderer {
    private ApiDocumentationRenderer() {
    }

    public static String render(ApplicationSchema schema, Map<String, List<OwnedRelation>> relations) {
        StringBuilder out = new StringBuilder();
        out.append("# API REST de ").append(schema.projectName()).append("\n\n")
                .append("Esta documentacion se genera desde el `ApplicationSchema` y describe el contrato real ")
                .append("de los DTO y controladores incluidos en este backend.\n\n")
                .append("## URL base\n\n")
                .append("Por defecto: `http://localhost:8080`. Si cambias `SERVER_PORT`, sustituye `8080` ")
                .append("por el puerto configurado. Todas las rutas de recursos comienzan con `/api`.\n\n")
                .append("## Convenciones\n\n")
                .append("- Las fechas usan `YYYY-MM-DD` y los valores fecha/hora usan `YYYY-MM-DDTHH:mm:ss`.\n")
                .append("- Una relacion simple se envia mediante la propiedad `...Id`.\n")
                .append("- Una relacion N:M se envia como un arreglo de IDs en la propiedad `...Ids`.\n")
                .append("- Un `400` indica un body invalido o campos obligatorios ausentes.\n")
                .append("- Un `404` indica que el recurso o un registro relacionado no existe.\n")
                .append("- Un `409` indica una restriccion de integridad o unicidad.\n\n");

        for (ApplicationEntity entity : schema.entities()) {
            renderEntity(out, entity, relations.getOrDefault(entity.id(), List.of()));
        }
        return out.toString();
    }

    private static void renderEntity(StringBuilder out, ApplicationEntity entity, List<OwnedRelation> relations) {
        String route = SpringNames.restRoute(entity.technicalName());
        String idName = entity.fields().stream().filter(ApplicationField::primaryKey)
                .findFirst().orElseThrow().technicalName();
        String create = json(entity, relations, ExampleKind.CREATE);
        String update = json(entity, relations, ExampleKind.UPDATE);
        String response = json(entity, relations, ExampleKind.RESPONSE);

        out.append("## ").append(entity.name()).append("\n\n")
                .append("Ruta del recurso: `").append(route).append("`\n\n")
                .append("### Campos\n\n")
                .append("| Propiedad JSON | Tipo | Obligatoria | Descripcion |\n")
                .append("|---|---|---|---|\n");
        for (ApplicationField field : entity.fields()) {
            out.append("| `").append(field.technicalName()).append("` | ")
                    .append(jsonType(field.type())).append(" | ")
                    .append(field.generated() ? "No se envia al crear" : field.nullable() ? "No" : "Si")
                    .append(" | ").append(field.primaryKey() ? "Identificador primario" : "Atributo de " + entity.name())
                    .append(" |\n");
        }
        for (OwnedRelation relation : relations) {
            out.append("| `").append(relation.dtoPropertyName()).append("` | ")
                    .append(relation.kind() == OwnedRelation.Kind.MANY_TO_MANY ? "array de IDs" : "ID")
                    .append(" | ").append(relation.optional() ? "No" : "Si")
                    .append(" | Relacion con ").append(relation.target().name()).append(" |\n");
        }

        endpoint(out, "GET", route, "Lista todos los registros de " + entity.name() + ".",
                null, "200", "[\n" + indent(response, 2) + "\n]");
        endpoint(out, "GET", route + "/{" + idName + "}", "Obtiene un registro por su identificador.",
                null, "200, 404", response);
        endpoint(out, "POST", route, "Crea un registro.", create, "200, 400, 404, 409", response);
        endpoint(out, "PUT", route + "/{" + idName + "}", "Reemplaza los datos editables de un registro.",
                update, "200, 400, 404, 409", response);
        endpoint(out, "DELETE", route + "/{" + idName + "}", "Elimina un registro por su identificador.",
                null, "204, 404, 409", null);
    }

    private static void endpoint(StringBuilder out, String method, String route, String description,
                                 String body, String statuses, String response) {
        out.append("\n### `").append(method).append(' ').append(route).append("`\n\n")
                .append(description).append("\n\n")
                .append("Codigos principales: `").append(statuses.replace(", ", "`, `")).append("`.\n\n");
        if (body != null) {
            out.append("Body de ejemplo:\n\n```json\n").append(body).append("\n```\n\n");
        }
        if (response != null) {
            out.append("Respuesta de ejemplo:\n\n```json\n").append(response).append("\n```\n");
        } else {
            out.append("Respuesta sin body.\n");
        }
    }

    private static String json(ApplicationEntity entity, List<OwnedRelation> relations, ExampleKind kind) {
        List<String> entries = new ArrayList<>();
        for (ApplicationField field : entity.fields()) {
            if (kind == ExampleKind.CREATE && field.generated()) continue;
            if (kind == ExampleKind.UPDATE && field.primaryKey()) continue;
            entries.add("  \"" + field.technicalName() + "\": " + example(field.type()));
        }
        for (OwnedRelation relation : relations) {
            String value = relation.kind() == OwnedRelation.Kind.MANY_TO_MANY ? "[1, 2]" : "1";
            entries.add("  \"" + relation.dtoPropertyName() + "\": " + value);
        }
        return "{\n" + String.join(",\n", entries) + "\n}";
    }

    private static String jsonType(CanonicalType type) {
        return switch (type) {
            case STRING -> "string";
            case INTEGER, LONG -> "integer";
            case DECIMAL -> "number";
            case BOOLEAN -> "boolean";
            case DATE -> "date";
            case DATETIME -> "datetime";
        };
    }

    private static String example(CanonicalType type) {
        return switch (type) {
            case STRING -> "\"ejemplo\"";
            case INTEGER, LONG -> "1";
            case DECIMAL -> "10.50";
            case BOOLEAN -> "true";
            case DATE -> "\"2026-01-15\"";
            case DATETIME -> "\"2026-01-15T10:30:00\"";
        };
    }

    private static String indent(String value, int spaces) {
        String prefix = " ".repeat(spaces);
        return prefix + value.replace("\n", "\n" + prefix);
    }

    private enum ExampleKind { CREATE, UPDATE, RESPONSE }
}
