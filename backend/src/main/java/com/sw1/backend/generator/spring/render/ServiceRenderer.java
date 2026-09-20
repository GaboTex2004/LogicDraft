package com.sw1.backend.generator.spring.render;

import com.sw1.backend.generator.schema.*;
import com.sw1.backend.generator.spring.*;
import java.util.*;

public final class ServiceRenderer {
    private ServiceRenderer() {
    }

    public static String render(String basePackage, ApplicationEntity entity, List<OwnedRelation> relations) {
        String type = entity.technicalName();
        String ownRepo = SpringNames.lowerFirst(type) + "Repository";
        JavaSourceWriter out = new JavaSourceWriter().line("package " + basePackage + ".service;").line("")
                .line("import " + basePackage + ".dto.*;")
                .line("import " + basePackage + ".entity.*;")
                .line("import " + basePackage + ".repository.*;")
                .line("import " + basePackage + ".error.ResourceNotFoundException;")
                .line("import java.util.*;")
                .line("import org.springframework.stereotype.Service;")
                .line("import org.springframework.transaction.annotation.Transactional;").line("")
                .line("@Service").open("@Transactional public class " + type + "Service")
                .line("private final " + type + "Repository " + ownRepo + ";");
        for (OwnedRelation relation : relations) {
            String targetType = relation.target().technicalName();
            out.line("private final " + targetType + "Repository " + SpringNames.lowerFirst(targetType) + "Repository;");
        }
        out.line("");
        String parameters = type + "Repository " + ownRepo;
        for (OwnedRelation relation : relations) {
            String target = relation.target().technicalName();
            parameters += ", " + target + "Repository " + SpringNames.lowerFirst(target) + "Repository";
        }
        out.open("public " + type + "Service(" + parameters + ")").line("this." + ownRepo + " = " + ownRepo + ";");
        for (OwnedRelation relation : relations) {
            String repo = SpringNames.lowerFirst(relation.target().technicalName()) + "Repository";
            out.line("this." + repo + " = " + repo + ";");
        }
        out.close().line("");
        out.line("@Transactional(readOnly = true)")
                .open("public List<" + type + "Response> listar()")
                .line("return " + ownRepo + ".findAll().stream().map(this::toResponse).toList();").close().line("")
                .line("@Transactional(readOnly = true)")
                .open("public " + type + "Response obtenerPorId(" + idType(entity) + " id)")
                .line("return toResponse(buscar(id));").close().line("")
                .open("public " + type + "Response crear(" + type + "CreateRequest request)")
                .line(type + " entity = new " + type + "();");
        assignScalarFields(out, entity, "request", true);
        assignRelations(out, relations, "request");
        out.line("return toResponse(" + ownRepo + ".save(entity));").close().line("")
                .open("public " + type + "Response actualizar(" + idType(entity) + " id, " + type + "UpdateRequest request)")
                .line(type + " entity = buscar(id);");
        assignScalarFields(out, entity, "request", false);
        assignRelations(out, relations, "request");
        out.line("return toResponse(" + ownRepo + ".save(entity));").close().line("")
                .open("public void eliminar(" + idType(entity) + " id)")
                .line(ownRepo + ".delete(buscar(id));").close().line("")
                .open("private " + type + " buscar(" + idType(entity) + " id)")
                .line("return " + ownRepo + ".findById(id).orElseThrow(() -> new ResourceNotFoundException(\""
                        + entity.name() + " no encontrado\"));").close().line("")
                .open("private " + type + "Response toResponse(" + type + " entity)");
        List<String> values = new ArrayList<>();
        entity.fields().forEach(field -> values.add("entity.get" + EntityRenderer.upperFirst(field.technicalName()) + "()"));
        for (OwnedRelation relation : relations) {
            String getter = "entity.get" + EntityRenderer.upperFirst(relation.propertyName()) + "()";
            String pkGetter = "get" + EntityRenderer.upperFirst(DtoRenderer.primaryKey(relation.target()).technicalName()) + "()";
            values.add(relation.kind() == OwnedRelation.Kind.MANY_TO_MANY
                    ? getter + ".stream().map(item -> item." + pkGetter + ").collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new))"
                    : getter + " == null ? null : " + getter + "." + pkGetter);
        }
        out.line("return new " + type + "Response(");
        for (int i = 0; i < values.size(); i++) out.line("        " + values.get(i) + (i + 1 == values.size() ? "" : ","));
        out.line(");").close();
        return out.close().toString();
    }

    private static void assignScalarFields(JavaSourceWriter out, ApplicationEntity entity, String request, boolean create) {
        for (ApplicationField field : entity.fields()) {
            if ((create && field.generated()) || (!create && field.primaryKey())) continue;
            String suffix = EntityRenderer.upperFirst(field.technicalName());
            out.line("entity.set" + suffix + "(" + request + "." + field.technicalName() + "());");
        }
    }

    private static void assignRelations(JavaSourceWriter out, List<OwnedRelation> relations, String request) {
        for (OwnedRelation relation : relations) {
            String setter = "entity.set" + EntityRenderer.upperFirst(relation.propertyName());
            String repo = SpringNames.lowerFirst(relation.target().technicalName()) + "Repository";
            String requestValue = request + "." + relation.dtoPropertyName() + "()";
            if (relation.kind() == OwnedRelation.Kind.MANY_TO_MANY) {
                String ids = relation.propertyName() + "Ids";
                String items = relation.propertyName() + "Values";
                out.line("Set<" + idType(relation.target()) + "> " + ids + " = " + requestValue
                        + " == null ? Set.of() : " + requestValue + ";")
                        .line("Set<" + relation.target().technicalName() + "> " + items
                                + " = new LinkedHashSet<>(" + repo + ".findAllById(" + ids + ")); ")
                        .open("if (" + items + ".size() != " + ids + ".size())")
                        .line("throw new ResourceNotFoundException(\"Una relacion de " + relation.target().name() + " no existe\");")
                        .close()
                        .line(setter + "(" + items + ");");
            } else {
                String lookup = repo + ".findById(" + requestValue + ").orElseThrow(() -> new ResourceNotFoundException(\""
                        + relation.target().name() + " relacionado no existe\"))";
                out.line(setter + "(" + (relation.optional() ? requestValue + " == null ? null : " + lookup : lookup) + ");");
            }
        }
    }

    private static String idType(ApplicationEntity entity) {
        return SpringTypeMapper.map(DtoRenderer.primaryKey(entity).type()).javaType();
    }
}
