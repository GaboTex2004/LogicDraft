package com.sw1.backend.generator.flutter;

import com.sw1.backend.generator.schema.ApplicationEntity;
import com.sw1.backend.generator.schema.ApplicationField;
import com.sw1.backend.generator.schema.ApplicationSchema;
import com.sw1.backend.generator.schema.CanonicalType;
import com.sw1.backend.generator.spring.OwnedRelation;
import com.sw1.backend.generator.spring.RelationshipPlanner;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class FlutterContract {
    private FlutterContract() {
    }

    public static Map<String, List<OwnedRelation>> relations(ApplicationSchema schema) {
        return RelationshipPlanner.plan(schema);
    }

    public static List<Field> responseFields(ApplicationEntity entity, List<OwnedRelation> relations) {
        List<Field> fields = new ArrayList<>();
        entity.fields().forEach(field -> fields.add(scalar(field)));
        relations.forEach(relation -> fields.add(relation(relation)));
        return List.copyOf(fields);
    }

    public static List<Field> createFields(ApplicationEntity entity, List<OwnedRelation> relations) {
        List<Field> fields = new ArrayList<>();
        entity.fields().stream().filter(field -> !field.generated()).forEach(field -> fields.add(scalar(field)));
        relations.forEach(relation -> fields.add(relation(relation)));
        return List.copyOf(fields);
    }

    public static List<Field> updateFields(ApplicationEntity entity, List<OwnedRelation> relations) {
        List<Field> fields = new ArrayList<>();
        entity.fields().stream().filter(field -> !field.primaryKey()).forEach(field -> fields.add(scalar(field)));
        relations.forEach(relation -> fields.add(relation(relation)));
        return List.copyOf(fields);
    }

    public static ApplicationField primaryKey(ApplicationEntity entity) {
        return entity.fields().stream().filter(ApplicationField::primaryKey).findFirst().orElseThrow();
    }

    private static Field scalar(ApplicationField field) {
        return new Field(field.technicalName(), field.name(), field.type(), field.nullable(), field.primaryKey(),
                field.generated(), false, false);
    }

    private static Field relation(OwnedRelation relation) {
        ApplicationField pk = primaryKey(relation.target());
        return new Field(relation.dtoPropertyName(), relation.target().name(), pk.type(), relation.optional(), false,
                false, true, relation.kind() == OwnedRelation.Kind.MANY_TO_MANY);
    }

    public record Field(String name, String label, CanonicalType type, boolean nullable, boolean primaryKey,
                        boolean generated, boolean relation, boolean collection) {
        public String dartType() {
            String value = collection ? "List<" + FlutterTypeMapper.dartType(type) + ">" : FlutterTypeMapper.dartType(type);
            return nullable ? value + "?" : value;
        }
    }
}
