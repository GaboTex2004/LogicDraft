package com.sw1.backend.generator.spring.render;

import com.sw1.backend.generator.schema.*;
import com.sw1.backend.generator.spring.*;
import java.util.*;

public final class DtoRenderer {
    private DtoRenderer() {
    }

    public static String createRequest(String basePackage, ApplicationEntity entity, List<OwnedRelation> relations) {
        return request(basePackage, entity, relations, true);
    }

    public static String updateRequest(String basePackage, ApplicationEntity entity, List<OwnedRelation> relations) {
        return request(basePackage, entity, relations, false);
    }

    public static String response(String basePackage, ApplicationEntity entity, List<OwnedRelation> relations) {
        List<Component> components = new ArrayList<>();
        entity.fields().forEach(field -> components.add(new Component(null,
                SpringTypeMapper.map(field.type()).javaType(), field.technicalName(), SpringTypeMapper.map(field.type()).javaImport())));
        relations.forEach(relation -> components.add(relationComponent(relation, null)));
        return record(basePackage, entity.technicalName() + "Response", components);
    }

    private static String request(String basePackage, ApplicationEntity entity, List<OwnedRelation> relations, boolean create) {
        List<Component> components = new ArrayList<>();
        for (ApplicationField field : entity.fields()) {
            if ((create && field.generated()) || (!create && field.primaryKey())) continue;
            String annotation = field.nullable() ? null
                    : field.type() == CanonicalType.STRING ? "@NotBlank" : "@NotNull";
            components.add(new Component(annotation, SpringTypeMapper.map(field.type()).javaType(),
                    field.technicalName(), SpringTypeMapper.map(field.type()).javaImport()));
        }
        for (OwnedRelation relation : relations) {
            String annotation = relation.optional() ? null
                    : relation.kind() == OwnedRelation.Kind.MANY_TO_MANY ? "@NotEmpty" : "@NotNull";
            components.add(relationComponent(relation, annotation));
        }
        return record(basePackage, entity.technicalName() + (create ? "CreateRequest" : "UpdateRequest"), components);
    }

    private static Component relationComponent(OwnedRelation relation, String annotation) {
        ApplicationField pk = primaryKey(relation.target());
        String idType = SpringTypeMapper.map(pk.type()).javaType();
        String type = relation.kind() == OwnedRelation.Kind.MANY_TO_MANY ? "Set<" + idType + ">" : idType;
        return new Component(annotation, type, relation.dtoPropertyName(), SpringTypeMapper.map(pk.type()).javaImport());
    }

    private static String record(String basePackage, String name, List<Component> components) {
        JavaSourceWriter out = new JavaSourceWriter().line("package " + basePackage + ".dto;").line("");
        Set<String> imports = new TreeSet<>();
        components.stream().map(Component::typeImport).filter(Objects::nonNull).forEach(imports::add);
        if (components.stream().anyMatch(component -> component.type().startsWith("Set<"))) imports.add("java.util.Set");
        components.stream().map(Component::annotation).filter(Objects::nonNull).forEach(annotation -> imports.add(switch (annotation) {
            case "@NotBlank" -> "jakarta.validation.constraints.NotBlank";
            case "@NotEmpty" -> "jakarta.validation.constraints.NotEmpty";
            default -> "jakarta.validation.constraints.NotNull";
        }));
        imports.forEach(value -> out.line("import " + value + ";"));
        if (!imports.isEmpty()) out.line("");
        out.line("public record " + name + "(");
        for (int index = 0; index < components.size(); index++) {
            Component component = components.get(index);
            String prefix = component.annotation() == null ? "        " : "        " + component.annotation() + " ";
            out.line(prefix + component.type() + " " + component.name() + (index + 1 == components.size() ? "" : ","));
        }
        out.line(") {}");
        return out.toString();
    }

    static ApplicationField primaryKey(ApplicationEntity entity) {
        return entity.fields().stream().filter(ApplicationField::primaryKey).findFirst().orElseThrow();
    }

    private record Component(String annotation, String type, String name, String typeImport) {}
}
