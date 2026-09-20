package com.sw1.backend.generator.spring.render;

import com.sw1.backend.generator.schema.*;
import com.sw1.backend.generator.spring.*;
import com.sw1.backend.generator.naming.TechnicalNameNormalizer;
import java.util.*;

public final class EntityRenderer {
    private EntityRenderer() {
    }

    public static String render(String basePackage, ApplicationEntity entity, List<OwnedRelation> relations) {
        JavaSourceWriter out = new JavaSourceWriter().line("package " + basePackage + ".entity;").line("");
        Set<String> imports = new TreeSet<>();
        imports.add("jakarta.persistence.*");
        for (ApplicationField field : entity.fields()) {
            String typeImport = SpringTypeMapper.map(field.type()).javaImport();
            if (typeImport != null) imports.add(typeImport);
        }
        if (relations.stream().anyMatch(relation -> relation.kind() == OwnedRelation.Kind.MANY_TO_MANY)) {
            imports.add("java.util.LinkedHashSet");
            imports.add("java.util.Set");
        }
        imports.forEach(value -> out.line("import " + value + ";"));
        out.line("").line("@Entity");
        if (entity.association() == null) {
            out.line("@Table(name = \"" + entityTable(entity) + "\")");
        } else {
            List<String> foreignKeys = entity.association().endpoints().stream()
                    .map(ApplicationAssociationEndpoint::foreignKeyName).toList();
            out.line("@Table(name = \"" + entityTable(entity) + "\",")
                    .line("        uniqueConstraints = @UniqueConstraint(name = \"uk_" + entityTable(entity)
                            + "_pair\", columnNames = {\"" + foreignKeys.get(0) + "\", \""
                            + foreignKeys.get(1) + "\"}))");
        }
        out.open("public class " + entity.technicalName());
        for (ApplicationField field : entity.fields()) {
            if (field.primaryKey()) {
                out.line("@Id");
                if (field.generated()) out.line("@GeneratedValue(strategy = GenerationType.IDENTITY)");
            }
            out.line("@Column(name = \"" + SpringNames.sqlName(field.technicalName(), "columna")
                    + "\", nullable = " + field.nullable() + ")");
            out.line("private " + SpringTypeMapper.map(field.type()).javaType() + " " + field.technicalName() + ";").line("");
        }
        for (OwnedRelation relation : relations) {
            renderRelationField(out, entity, relation);
        }
        out.open("public " + entity.technicalName() + "()").close().line("");
        for (ApplicationField field : entity.fields()) {
            String type = SpringTypeMapper.map(field.type()).javaType();
            String suffix = upperFirst(field.technicalName());
            out.open("public " + type + " get" + suffix + "()").line("return " + field.technicalName() + ";").close().line("");
            out.open("public void set" + suffix + "(" + type + " " + field.technicalName() + ")")
                    .line("this." + field.technicalName() + " = " + field.technicalName() + ";").close().line("");
        }
        for (OwnedRelation relation : relations) {
            String type = relation.kind() == OwnedRelation.Kind.MANY_TO_MANY
                    ? "Set<" + relation.target().technicalName() + ">" : relation.target().technicalName();
            String suffix = upperFirst(relation.propertyName());
            out.open("public " + type + " get" + suffix + "()").line("return " + relation.propertyName() + ";").close().line("");
            out.open("public void set" + suffix + "(" + type + " " + relation.propertyName() + ")")
                    .line("this." + relation.propertyName() + " = " + relation.propertyName() + ";").close().line("");
        }
        return out.close().toString();
    }

    private static void renderRelationField(JavaSourceWriter out, ApplicationEntity owner, OwnedRelation relation) {
        String property = relation.propertyName();
        if (relation.kind() == OwnedRelation.Kind.MANY_TO_MANY) {
            String table = joinTable(relation);
            String ownerColumn = SpringNames.sqlName(owner.technicalName() + "Id", "join column");
            String targetColumn = SpringNames.sqlName(relation.target().technicalName() + "Id", "join column");
            out.line("@ManyToMany")
                    .line("@JoinTable(name = \"" + table + "\",")
                    .line("        joinColumns = @JoinColumn(name = \"" + ownerColumn + "\", nullable = false),")
                    .line("        inverseJoinColumns = @JoinColumn(name = \"" + targetColumn + "\", nullable = false),")
                    .line("        uniqueConstraints = @UniqueConstraint(name = \"uk_" + table
                            + "_pair\", columnNames = {\"" + ownerColumn + "\", \"" + targetColumn + "\"}))")
                    .line("private Set<" + relation.target().technicalName() + "> " + property + " = new LinkedHashSet<>();").line("");
            return;
        }
        if (relation.kind() == OwnedRelation.Kind.MANY_TO_ONE) {
            out.line("@ManyToOne(fetch = FetchType.LAZY, optional = " + relation.optional() + ")");
        } else {
            out.line("@OneToOne(fetch = FetchType.LAZY, optional = " + relation.optional() + ")");
        }
        String joinColumn = relation.joinColumnName() == null
                ? SpringNames.sqlName(property + "Id", "foreign key") : relation.joinColumnName();
        out.line("@JoinColumn(name = \"" + joinColumn
                + "\", nullable = " + relation.optional()
                + (relation.kind() == OwnedRelation.Kind.ONE_TO_ONE ? ", unique = true" : "") + ")")
                .line("private " + relation.target().technicalName() + " " + property + ";").line("");
    }

    public static String joinTable(OwnedRelation relation) {
        String requested = relation.relationship().joinTableName();
        String relationName = relation.relationship().name();
        String source = requested != null
                ? TechnicalNameNormalizer.typeName(requested)
                : relationName != null
                    ? relation.owner().technicalName() + relation.target().technicalName()
                        + TechnicalNameNormalizer.typeName(relationName)
                    : relation.owner().technicalName() + relation.target().technicalName();
        String table = SpringNames.sqlName(source, "join table");
        if (table.length() > 55) {
            throw new SpringGeneratorException("La tabla intermedia '" + table
                    + "' es demasiado larga para nombrar su restriccion UNIQUE en PostgreSQL.");
        }
        return table;
    }

    public static String entityTable(ApplicationEntity entity) {
        return entity.association() == null
                ? SpringNames.sqlName(entity.technicalName(), "tabla") : entity.association().tableName();
    }

    static String upperFirst(String value) {
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }
}
