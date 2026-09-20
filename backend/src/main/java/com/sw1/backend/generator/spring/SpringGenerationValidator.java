package com.sw1.backend.generator.spring;

import com.sw1.backend.generator.schema.*;
import com.sw1.backend.generator.validation.ApplicationSchemaValidator;
import java.util.*;

public final class SpringGenerationValidator {
    private SpringGenerationValidator() {
    }

    public static void validate(ApplicationSchema schema, Map<String, List<OwnedRelation>> relations) {
        ApplicationSchemaValidator.validate(schema);
        SpringNames.basePackage(schema.technicalName());
        SpringNames.databaseName(schema.technicalName());
        Set<String> generatedTables = new HashSet<>();
        for (ApplicationEntity entity : schema.entities()) {
            SpringNames.validateJavaIdentifier(entity.technicalName(), "entidad " + entity.name());
            String entityTable = com.sw1.backend.generator.spring.render.EntityRenderer.entityTable(entity);
            if (!generatedTables.add(entityTable)) {
                throw new SpringGeneratorException("La tabla '" + entityTable + "' esta repetida.");
            }
            Set<String> javaProperties = new HashSet<>();
            Set<String> columns = new HashSet<>();
            for (ApplicationField field : entity.fields()) {
                SpringNames.validateJavaIdentifier(field.technicalName(), "atributo " + field.name());
                String column = SpringNames.sqlName(field.technicalName(), "columna " + entity.name() + "." + field.name());
                if (!javaProperties.add(field.technicalName()) || !columns.add(column)) {
                    throw new SpringGeneratorException("Hay una colision de campos generados en " + entity.name() + ".");
                }
            }
            for (OwnedRelation relation : relations.get(entity.id())) {
                SpringNames.validateJavaIdentifier(relation.propertyName(), "relacion " + relation.relationship().id());
                if (!javaProperties.add(relation.propertyName()) || !javaProperties.add(relation.dtoPropertyName())) {
                    throw new SpringGeneratorException("La relacion '" + relation.relationship().id()
                            + "' colisiona con otro campo generado en " + entity.name() + ".");
                }
                if (relation.kind() == OwnedRelation.Kind.MANY_TO_MANY) {
                    String joinTable = com.sw1.backend.generator.spring.render.EntityRenderer.joinTable(relation);
                    if (!generatedTables.add(joinTable)) {
                        throw new SpringGeneratorException("La tabla intermedia '" + joinTable
                                + "' esta repetida; asigna nombres distintos a las relaciones N:M.");
                    }
                } else {
                    String foreignKey = relation.joinColumnName() == null
                            ? SpringNames.sqlName(relation.propertyName() + "Id", "foreign key")
                            : relation.joinColumnName();
                    if (!columns.add(foreignKey)) {
                        throw new SpringGeneratorException("La relacion '" + relation.relationship().id()
                                + "' colisiona con una columna generada en " + entity.name() + ".");
                    }
                }
            }
        }
    }
}
