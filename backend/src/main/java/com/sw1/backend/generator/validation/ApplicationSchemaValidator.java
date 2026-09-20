package com.sw1.backend.generator.validation;

import com.sw1.backend.generator.schema.*;
import java.util.HashSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

public final class ApplicationSchemaValidator {
    private ApplicationSchemaValidator() {
    }

    public static ApplicationSchema validate(ApplicationSchema schema) {
        if (schema == null || schema.entities() == null || schema.relationships() == null) {
            throw new ApplicationSchemaException("ApplicationSchema tiene una estructura invalida.");
        }
        requireName(schema.projectName(), "projectName");
        requireName(schema.applicationName(), "applicationName");
        requireTechnicalName(schema.technicalName(), "aplicacion");
        if (schema.entities().isEmpty()) {
            throw new ApplicationSchemaException("El diagrama debe contener al menos una entidad.");
        }
        Set<String> entityIds = new HashSet<>();
        Set<String> entityNames = new HashSet<>();
        Set<String> entityTechnicalNames = new HashSet<>();
        for (ApplicationEntity entity : schema.entities()) {
            if (entity == null || entity.fields() == null) {
                throw new ApplicationSchemaException("ApplicationSchema contiene una entidad invalida.");
            }
            requireId(entity.id(), "entidad " + entity.name());
            requireName(entity.name(), "entidad");
            requireTechnicalName(entity.technicalName(), "entidad " + entity.name());
            if (!entityIds.add(entity.id())) {
                throw new ApplicationSchemaException("El ID de entidad '" + entity.id() + "' esta duplicado.");
            }
            if (!entityNames.add(key(entity.name()))) {
                throw new ApplicationSchemaException("El nombre de entidad '" + entity.name() + "' esta duplicado.");
            }
            if (!entityTechnicalNames.add(key(entity.technicalName()))) {
                ApplicationEntity other = schema.entities().stream()
                        .filter(candidate -> !candidate.id().equals(entity.id()))
                        .filter(candidate -> key(candidate.technicalName()).equals(key(entity.technicalName())))
                        .findFirst().orElse(entity);
                throw new ApplicationSchemaException("Las entidades " + other.name() + " y " + entity.name()
                        + " producen el mismo nombre tecnico '" + entity.technicalName() + "'.");
            }
            validateFields(entity);
        }
        Set<String> relationshipIds = new HashSet<>();
        Set<String> relationshipDefinitions = new HashSet<>();
        Set<String> explicitJoinTables = new HashSet<>();
        Map<String, ApplicationRelationship> relationshipsById = new HashMap<>();
        for (ApplicationRelationship relationship : schema.relationships()) {
            if (relationship == null) {
                throw new ApplicationSchemaException("ApplicationSchema contiene una relacion invalida.");
            }
            requireId(relationship.id(), "relacion");
            if (!relationshipIds.add(relationship.id())) {
                throw new ApplicationSchemaException("El ID de relacion '" + relationship.id() + "' esta duplicado.");
            }
            relationshipsById.put(relationship.id(), relationship);
            if (!entityIds.contains(relationship.sourceEntityId())) {
                throw new ApplicationSchemaException("La relacion '" + relationship.id() + "' apunta a una entidad origen inexistente.");
            }
            if (!entityIds.contains(relationship.targetEntityId())) {
                throw new ApplicationSchemaException("La relacion '" + relationship.id() + "' apunta a una entidad destino inexistente.");
            }
            if (relationship.sourceEntityId().equals(relationship.targetEntityId())) {
                throw new ApplicationSchemaException("Las autorrelaciones no estan habilitadas en Generator V1.");
            }
            if (relationship.sourceCardinality() == null || relationship.targetCardinality() == null) {
                throw new ApplicationSchemaException("La relacion '" + relationship.id() + "' tiene cardinalidades invalidas.");
            }
            if (relationship.name() != null) requireName(relationship.name(), "relacion " + relationship.id());
            if (relationship.joinTableName() != null) {
                requireName(relationship.joinTableName(), "tabla intermedia " + relationship.id());
                if (!many(relationship.sourceCardinality()) || !many(relationship.targetCardinality())) {
                    throw new ApplicationSchemaException("La relacion '" + relationship.id()
                            + "' define tabla intermedia pero no es N:M.");
                }
                if (!explicitJoinTables.add(key(relationship.joinTableName()))) {
                    throw new ApplicationSchemaException("La tabla intermedia '" + relationship.joinTableName()
                            + "' esta repetida.");
                }
            }
            String forward = relationship.sourceEntityId() + "|" + relationship.sourceCardinality() + "|"
                    + relationship.targetEntityId() + "|" + relationship.targetCardinality();
            String reverse = relationship.targetEntityId() + "|" + relationship.targetCardinality() + "|"
                    + relationship.sourceEntityId() + "|" + relationship.sourceCardinality();
            String definition = (forward.compareTo(reverse) <= 0 ? forward : reverse) + "|"
                    + key(relationship.name() == null ? "" : relationship.name());
            if (!relationshipDefinitions.add(definition)) {
                throw new ApplicationSchemaException("La relacion '" + relationship.id() + "' esta duplicada.");
            }
        }
        validateAssociations(schema, relationshipsById);
        return schema;
    }

    private static void validateAssociations(ApplicationSchema schema,
                                             Map<String, ApplicationRelationship> relationshipsById) {
        Map<String, ApplicationEntity> entitiesById = new HashMap<>();
        schema.entities().forEach(entity -> entitiesById.put(entity.id(), entity));
        Set<String> structuralRelationships = new HashSet<>();
        Set<String> physicalTables = new HashSet<>();

        for (ApplicationEntity entity : schema.entities()) {
            ApplicationAssociation association = entity.association();
            String table = association == null ? sqlName(entity.technicalName()) : association.tableName();
            if (association != null) requirePhysicalName(table, 55, "tabla de asociacion " + entity.name());
            if (!physicalTables.add(key(table))) {
                throw new ApplicationSchemaException("La tabla fisica '" + table + "' esta repetida.");
            }
        }
        for (ApplicationRelationship relationship : schema.relationships()) {
            if (!many(relationship.sourceCardinality()) || !many(relationship.targetCardinality())) continue;
            ApplicationEntity source = entitiesById.get(relationship.sourceEntityId());
            ApplicationEntity target = entitiesById.get(relationship.targetEntityId());
            String requested = relationship.joinTableName() != null
                    ? relationship.joinTableName()
                    : source.technicalName() + target.technicalName()
                        + (relationship.name() == null ? ""
                            : com.sw1.backend.generator.naming.TechnicalNameNormalizer.typeName(relationship.name()));
            String table = sqlName(com.sw1.backend.generator.naming.TechnicalNameNormalizer.typeName(requested));
            if (!physicalTables.add(key(table)) && !isOriginalManyToManyOfAssociation(schema, relationship)) {
                throw new ApplicationSchemaException("La tabla intermedia fisica '" + table + "' esta repetida.");
            }
        }

        for (ApplicationEntity entity : schema.entities()) {
            ApplicationAssociation association = entity.association();
            if (association == null) continue;
            if (association.kind() != AssociationKind.MANY_TO_MANY_ASSOCIATION) {
                throw new ApplicationSchemaException("La entidad asociativa " + entity.name() + " tiene un kind invalido.");
            }
            if (!association.uniquePair()) {
                throw new ApplicationSchemaException("La entidad asociativa " + entity.name()
                        + " debe exigir unicidad para la pareja de foreign keys.");
            }
            if (association.endpoints() == null || association.endpoints().size() != 2) {
                throw new ApplicationSchemaException("La entidad asociativa " + entity.name()
                        + " debe definir exactamente dos endpoints.");
            }
            ApplicationField primaryKey = entity.fields().stream().filter(ApplicationField::primaryKey)
                    .findFirst().orElseThrow();
            if (!primaryKey.generated()
                    || primaryKey.type() != CanonicalType.INTEGER && primaryKey.type() != CanonicalType.LONG) {
                throw new ApplicationSchemaException("La entidad asociativa " + entity.name()
                        + " debe utilizar una primary key INTEGER o LONG generada.");
            }

            Set<AssociationEndpointRole> roles = new HashSet<>();
            Set<String> endpointEntities = new HashSet<>();
            Set<String> endpointRelationships = new HashSet<>();
            Set<String> foreignKeys = new HashSet<>();
            for (ApplicationAssociationEndpoint endpoint : association.endpoints()) {
                if (endpoint == null || endpoint.role() == null) {
                    throw new ApplicationSchemaException("La entidad asociativa " + entity.name()
                            + " contiene un endpoint invalido.");
                }
                if (!roles.add(endpoint.role())) {
                    throw new ApplicationSchemaException("La entidad asociativa " + entity.name()
                            + " repite el role de un endpoint.");
                }
                requireId(endpoint.entityId(), "entidad referenciada por " + entity.name());
                requireId(endpoint.relationshipId(), "relacion estructural de " + entity.name());
                requirePhysicalName(endpoint.foreignKeyName(), 63, "foreign key de " + entity.name());
                if (!endpointEntities.add(endpoint.entityId()) || endpoint.entityId().equals(entity.id())) {
                    throw new ApplicationSchemaException("La entidad asociativa " + entity.name()
                            + " debe referenciar dos entidades externas distintas.");
                }
                if (!entitiesById.containsKey(endpoint.entityId())) {
                    throw new ApplicationSchemaException("La entidad asociativa " + entity.name()
                            + " referencia una entidad inexistente.");
                }
                if (!endpointRelationships.add(endpoint.relationshipId())
                        || !structuralRelationships.add(endpoint.relationshipId())) {
                    throw new ApplicationSchemaException("La relacion estructural '" + endpoint.relationshipId()
                            + "' esta repetida en metadatos asociativos.");
                }
                if (!foreignKeys.add(key(endpoint.foreignKeyName()))) {
                    throw new ApplicationSchemaException("La entidad asociativa " + entity.name()
                            + " contiene foreign keys duplicadas.");
                }
                ApplicationRelationship relationship = relationshipsById.get(endpoint.relationshipId());
                if (relationship == null || !isStructuralRelationship(
                        relationship, endpoint.entityId(), entity.id())) {
                    throw new ApplicationSchemaException("La relacion estructural '" + endpoint.relationshipId()
                            + "' no conecta correctamente la entidad asociativa " + entity.name() + ".");
                }
            }
            if (!roles.equals(Set.of(AssociationEndpointRole.SOURCE, AssociationEndpointRole.TARGET))) {
                throw new ApplicationSchemaException("La entidad asociativa " + entity.name()
                        + " debe definir los roles SOURCE y TARGET.");
            }
            for (ApplicationField field : entity.fields()) {
                if (foreignKeys.contains(key(sqlName(field.technicalName())))) {
                    throw new ApplicationSchemaException("Un atributo de " + entity.name()
                            + " colisiona con una foreign key estructural.");
                }
            }
            for (ApplicationRelationship relationship : schema.relationships()) {
                if (endpointRelationships.contains(relationship.id())) continue;
                boolean samePair = endpointEntities.contains(relationship.sourceEntityId())
                        && endpointEntities.contains(relationship.targetEntityId());
                if (samePair && many(relationship.sourceCardinality()) && many(relationship.targetCardinality())) {
                    throw new ApplicationSchemaException("La entidad asociativa " + entity.name()
                            + " no puede coexistir con la relacion N:M original.");
                }
            }
        }
    }

    private static boolean isStructuralRelationship(ApplicationRelationship relationship,
                                                    String endpointEntityId, String associationEntityId) {
        if (relationship.sourceEntityId().equals(endpointEntityId)
                && relationship.targetEntityId().equals(associationEntityId)) {
            return relationship.sourceCardinality() == ApplicationCardinality.ONE_ONE
                    && many(relationship.targetCardinality());
        }
        if (relationship.sourceEntityId().equals(associationEntityId)
                && relationship.targetEntityId().equals(endpointEntityId)) {
            return many(relationship.sourceCardinality())
                    && relationship.targetCardinality() == ApplicationCardinality.ONE_ONE;
        }
        return false;
    }

    private static boolean isOriginalManyToManyOfAssociation(ApplicationSchema schema,
                                                               ApplicationRelationship relationship) {
        Set<String> pair = Set.of(relationship.sourceEntityId(), relationship.targetEntityId());
        return schema.entities().stream().map(ApplicationEntity::association).filter(Objects::nonNull)
                .anyMatch(association -> association.endpoints() != null && association.endpoints().size() == 2
                        && association.endpoints().stream().allMatch(Objects::nonNull)
                        && association.endpoints().stream().map(ApplicationAssociationEndpoint::entityId)
                            .collect(java.util.stream.Collectors.toSet()).equals(pair));
    }

    private static void requirePhysicalName(String value, int maxLength, String subject) {
        if (value == null || value.length() > maxLength || !value.matches("[a-z][a-z0-9_]*")) {
            throw new ApplicationSchemaException("El nombre fisico de " + subject + " no es valido.");
        }
    }

    private static String sqlName(String technicalName) {
        return technicalName.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
    }

    private static void validateFields(ApplicationEntity entity) {
        Set<String> ids = new HashSet<>();
        Set<String> names = new HashSet<>();
        Set<String> technicalNames = new HashSet<>();
        long primaryKeys = 0;
        for (ApplicationField field : entity.fields()) {
            if (field == null) {
                throw new ApplicationSchemaException("La entidad " + entity.name() + " contiene un atributo invalido.");
            }
            requireId(field.id(), "atributo " + field.name());
            requireName(field.name(), "atributo de " + entity.name());
            requireTechnicalName(field.technicalName(), "atributo " + field.name());
            if (!ids.add(field.id())) {
                throw new ApplicationSchemaException("El ID de atributo '" + field.id() + "' esta duplicado en " + entity.name() + ".");
            }
            if (!names.add(key(field.name()))) {
                throw new ApplicationSchemaException("El atributo " + field.name() + " esta duplicado en " + entity.name() + ".");
            }
            if (!technicalNames.add(key(field.technicalName()))) {
                throw new ApplicationSchemaException("Dos atributos de " + entity.name()
                        + " producen el mismo nombre tecnico '" + field.technicalName() + "'.");
            }
            if (field.type() == null) {
                throw new ApplicationSchemaException("El atributo " + field.name() + " de " + entity.name()
                        + " utiliza un tipo no soportado.");
            }
            if (field.primaryKey()) primaryKeys++;
            if (field.primaryKey() && field.nullable()) {
                throw new ApplicationSchemaException("La primary key " + field.name() + " de " + entity.name() + " no puede ser nullable.");
            }
            if (field.generated() && (!field.primaryKey()
                    || (field.type() != CanonicalType.INTEGER && field.type() != CanonicalType.LONG))) {
                throw new ApplicationSchemaException("El atributo " + field.name() + " de " + entity.name()
                        + " tiene una configuracion generated invalida.");
            }
        }
        if (primaryKeys == 0) {
            throw new ApplicationSchemaException("La entidad " + entity.name() + " no tiene una primary key.");
        }
        if (primaryKeys > 1) {
            throw new ApplicationSchemaException("La entidad " + entity.name() + " tiene mas de una primary key.");
        }
    }

    private static void requireId(String id, String subject) {
        if (id == null || id.isBlank() || !id.equals(id.strip()) || id.length() > 200) {
            throw new ApplicationSchemaException("El ID de " + subject + " no es valido.");
        }
    }

    private static void requireName(String value, String subject) {
        if (value == null || value.isBlank() || !value.equals(value.strip()) || value.length() > 200) {
            throw new ApplicationSchemaException("El nombre de " + subject + " no es valido.");
        }
    }

    private static void requireTechnicalName(String value, String subject) {
        if (value == null || !value.matches("[A-Za-z][A-Za-z0-9]*")) {
            throw new ApplicationSchemaException("El nombre tecnico de " + subject + " no es valido.");
        }
    }

    private static String key(String value) {
        return value.toLowerCase(Locale.ROOT);
    }

    private static boolean many(ApplicationCardinality cardinality) {
        return cardinality == ApplicationCardinality.ZERO_MANY
                || cardinality == ApplicationCardinality.ONE_MANY;
    }
}
