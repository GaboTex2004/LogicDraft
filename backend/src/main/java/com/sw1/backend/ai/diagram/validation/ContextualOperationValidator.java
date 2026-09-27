package com.sw1.backend.ai.diagram.validation;

import com.sw1.backend.ai.client.AiServiceException;
import com.sw1.backend.ai.diagram.dto.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import static com.sw1.backend.ai.diagram.validation.DiagramContextMapper.key;

public final class ContextualOperationValidator {
    private ContextualOperationValidator() {}

    public static DiagramInterpretResponse validate(DiagramContext context, DiagramInterpretResponse response) {
        Map<String, Map<String, AttributeDefinition>> entities = new LinkedHashMap<>();
        for (EntityDefinition entity : context.entities()) {
            Map<String, AttributeDefinition> attributes = new LinkedHashMap<>();
            for (AttributeDefinition attribute : entity.attributes()) attributes.put(key(attribute.name()), attribute);
            entities.put(key(entity.name()), attributes);
        }
        Set<List<String>> relationships = new HashSet<>();
        List<RelationshipDefinition> virtualRelationships = new ArrayList<>(context.relationships());
        Map<String, RelationshipDefinition> relationshipsById = new LinkedHashMap<>();
        for (RelationshipDefinition relation : context.relationships()) {
            relationships.add(relationKey(relation));
            if (relation.id() != null) relationshipsById.put(relation.id(), relation);
        }
        List<DiagramOperation> result = new ArrayList<>();
        // A virtual plan only: operations may reference an entity created earlier in the batch.
        for (DiagramOperation operation : response.operations()) {
            switch (operation.type()) {
                case ADD_ENTITY -> {
                    EntityDefinition entity = operation.entity();
                    if (entities.containsKey(key(entity.name()))) throw conflict("Ya existe una entidad con ese nombre");
                    Map<String, AttributeDefinition> attributes = new LinkedHashMap<>();
                    for (AttributeDefinition attribute : entity.attributes()) {
                        if (attributes.putIfAbsent(key(attribute.name()), attribute) != null)
                            throw conflict("La entidad propuesta contiene atributos duplicados");
                    }
                    entities.put(key(entity.name()), attributes);
                }
                case DELETE_ENTITY -> {
                    String entityKey = key(operation.entityName());
                    if (entities.remove(entityKey) == null) throw conflict("La entidad a eliminar no existe");
                    virtualRelationships.removeIf(relation -> key(relation.sourceEntity()).equals(entityKey)
                            || key(relation.targetEntity()).equals(entityKey));
                    relationships.clear();
                    virtualRelationships.forEach(relation -> relationships.add(relationKey(relation)));
                }
                case RENAME_ENTITY -> {
                    String previous = key(operation.entityName()), next = key(operation.newName());
                    Map<String, AttributeDefinition> attributes = entities.remove(previous);
                    if (attributes == null) throw conflict("La entidad a renombrar no existe");
                    if (entities.putIfAbsent(next, attributes) != null) throw conflict("Ya existe una entidad con el nuevo nombre");
                    for (int index = 0; index < virtualRelationships.size(); index++) {
                        RelationshipDefinition relation = virtualRelationships.get(index);
                        virtualRelationships.set(index, new RelationshipDefinition(
                                key(relation.sourceEntity()).equals(previous) ? operation.newName() : relation.sourceEntity(),
                                key(relation.targetEntity()).equals(previous) ? operation.newName() : relation.targetEntity(),
                                relation.sourceCardinality(), relation.targetCardinality(), relation.name(),
                                relation.joinTableName(), relation.id()));
                    }
                    relationships.clear();
                    virtualRelationships.forEach(relation -> relationships.add(relationKey(relation)));
                }
                case ADD_ATTRIBUTE -> {
                    Map<String, AttributeDefinition> attributes = entities.get(key(operation.entityName()));
                    if (attributes == null) throw conflict("La entidad destino no existe");
                    AttributeDefinition proposed = operation.attribute();
                    AttributeDefinition existing = attributes.get(key(proposed.name()));
                    if (existing != null) {
                        if (existing.dataType() == proposed.dataType()
                                && existing.primaryKey() == proposed.primaryKey()
                                && existing.nullable() == proposed.nullable()) continue;
                        throw conflict("El atributo propuesto entra en conflicto con un atributo existente");
                    }
                    attributes.put(key(proposed.name()), proposed);
                }
                case DELETE_ATTRIBUTE -> {
                    Map<String, AttributeDefinition> attributes = requiredAttributes(entities, operation.entityName());
                    if (attributes.remove(key(operation.attributeName())) == null)
                        throw conflict("El atributo a eliminar no existe");
                }
                case RENAME_ATTRIBUTE -> {
                    Map<String, AttributeDefinition> attributes = requiredAttributes(entities, operation.entityName());
                    AttributeDefinition attribute = attributes.remove(key(operation.attributeName()));
                    if (attribute == null) throw conflict("El atributo a renombrar no existe");
                    if (attributes.putIfAbsent(key(operation.newName()), new AttributeDefinition(operation.newName(),
                            attribute.dataType(), attribute.primaryKey(), attribute.nullable())) != null)
                        throw conflict("Ya existe un atributo con el nuevo nombre");
                }
                case CHANGE_ATTRIBUTE_TYPE, SET_ATTRIBUTE_PRIMARY_KEY, SET_ATTRIBUTE_NULLABLE -> {
                    Map<String, AttributeDefinition> attributes = requiredAttributes(entities, operation.entityName());
                    String attributeKey = key(operation.attributeName());
                    AttributeDefinition attribute = attributes.get(attributeKey);
                    if (attribute == null) throw conflict("El atributo a modificar no existe");
                    AttributeDefinition updated = switch (operation.type()) {
                        case CHANGE_ATTRIBUTE_TYPE -> new AttributeDefinition(attribute.name(), operation.dataType(),
                                attribute.primaryKey(), attribute.nullable());
                        case SET_ATTRIBUTE_PRIMARY_KEY -> new AttributeDefinition(attribute.name(), attribute.dataType(),
                                operation.value(), operation.value() ? false : attribute.nullable());
                        case SET_ATTRIBUTE_NULLABLE -> {
                            if (operation.value() && attribute.primaryKey()) throw conflict("Una PK no puede aceptar null");
                            yield new AttributeDefinition(attribute.name(), attribute.dataType(), attribute.primaryKey(), operation.value());
                        }
                        default -> throw new IllegalStateException();
                    };
                    attributes.put(attributeKey, updated);
                }
                case ADD_RELATIONSHIP -> {
                    RelationshipDefinition relation = operation.relationship();
                    if (!entities.containsKey(key(relation.sourceEntity())) || !entities.containsKey(key(relation.targetEntity())))
                        throw conflict("La relacion referencia una entidad inexistente");
                    if (key(relation.sourceEntity()).equals(key(relation.targetEntity())))
                        throw conflict("Las autorrelaciones nuevas no estan habilitadas en este MVP");
                    if (relation.sourceCardinality() == null || relation.targetCardinality() == null)
                        throw conflict("La cardinalidad propuesta no es valida");
                    if (!relationships.add(relationKey(relation))) continue;
                    virtualRelationships.add(relation);
                }
                case DELETE_RELATIONSHIP -> {
                    RelationshipDefinition relation = findRelationship(virtualRelationships,
                            operation.relationship().sourceEntity(), operation.relationship().targetEntity(),
                            operation.relationship().name());
                    virtualRelationships.remove(relation);
                    relationships.remove(relationKey(relation));
                }
                case UPDATE_RELATIONSHIP -> {
                    RelationshipDefinition update = operation.relationship();
                    RelationshipDefinition relation = findRelationship(virtualRelationships, update.sourceEntity(),
                            update.targetEntity(), update.name());
                    virtualRelationships.remove(relation);
                    relationships.remove(relationKey(relation));
                    RelationshipDefinition updated = new RelationshipDefinition(update.sourceEntity(), update.targetEntity(),
                            update.sourceCardinality(), update.targetCardinality(), relation.name(), relation.joinTableName(), relation.id());
                    virtualRelationships.add(updated);
                    relationships.add(relationKey(updated));
                }
                case CREATE_ASSOCIATION -> {
                    AssociationCreationDefinition association = operation.association();
                    if (!entities.containsKey(key(association.sourceEntity())) || !entities.containsKey(key(association.targetEntity())))
                        throw conflict("La asociacion referencia una entidad inexistente");
                    if (key(association.sourceEntity()).equals(key(association.targetEntity())))
                        throw conflict("Las autorrelaciones no estan habilitadas");
                    if (entities.containsKey(key(association.associationEntityName())))
                        throw conflict("Ya existe una entidad con el nombre asociativo solicitado");
                    Map<String, AttributeDefinition> attributes = new LinkedHashMap<>();
                    for (AttributeDefinition attribute : association.attributes()) {
                        if (attribute.primaryKey() || attributes.putIfAbsent(key(attribute.name()), attribute) != null)
                            throw conflict("Los atributos propios de la asociacion no son validos");
                    }
                    entities.put(key(association.associationEntityName()), attributes);
                }
                case DELETE_ASSOCIATION -> {
                    boolean exists = context.associations().stream().anyMatch(association ->
                            key(association.entityName()).equals(key(operation.associationEntityName())));
                    if (!exists || entities.remove(key(operation.associationEntityName())) == null)
                        throw conflict("La entidad asociativa a eliminar no existe");
                }
                case CONVERT_MANY_TO_MANY_ASSOCIATION -> {
                    AssociationConversionDefinition conversion = operation.conversion();
                    RelationshipDefinition relation = relationshipsById.get(conversion.relationshipId());
                    if (relation == null) throw conflict("La relacion indicada no existe o ya fue convertida");
                    Set<String> expected = Set.of(key(conversion.sourceEntity()), key(conversion.targetEntity()));
                    Set<String> actual = Set.of(key(relation.sourceEntity()), key(relation.targetEntity()));
                    if (!actual.equals(expected)) throw conflict("Los extremos no coinciden con la relacion indicada");
                    if (!many(relation.sourceCardinality()) || !many(relation.targetCardinality()))
                        throw conflict("La relacion indicada no es muchos a muchos");
                    long candidates = context.relationships().stream()
                            .filter(candidate -> Set.of(key(candidate.sourceEntity()), key(candidate.targetEntity())).equals(actual))
                            .filter(candidate -> many(candidate.sourceCardinality()) && many(candidate.targetCardinality()))
                            .count();
                    if (candidates > 1) throw conflict("Existen varias relaciones N:M candidatas; selecciona o identifica una relacion concreta");
                    if (entities.containsKey(key(conversion.associationEntityName())))
                        throw conflict("Ya existe una entidad con el nombre asociativo solicitado");
                    boolean alreadyConverted = context.associations().stream().anyMatch(association ->
                            association.endpointEntityNames().stream().map(DiagramContextMapper::key).collect(java.util.stream.Collectors.toSet())
                                    .equals(actual));
                    if (alreadyConverted) throw conflict("La relacion ya fue convertida en una entidad asociativa");
                    Set<String> attributeNames = new HashSet<>();
                    Map<String, AttributeDefinition> associationAttributes = new LinkedHashMap<>();
                    for (AttributeDefinition attribute : conversion.attributes()) {
                        if (attribute.primaryKey()) throw conflict("Los atributos propios no pueden reemplazar la PK generada");
                        if (!attributeNames.add(key(attribute.name())))
                            throw conflict("La conversion contiene atributos propios duplicados");
                        associationAttributes.put(key(attribute.name()), attribute);
                    }
                    entities.put(key(conversion.associationEntityName()), associationAttributes);
                    relationshipsById.remove(conversion.relationshipId());
                }
            }
            result.add(operation);
        }
        return new DiagramInterpretResponse(List.copyOf(result));
    }

    private static List<String> relationKey(RelationshipDefinition relation) {
        String source = key(relation.sourceEntity()), target = key(relation.targetEntity());
        if (relation.sourceCardinality() == null || relation.targetCardinality() == null)
            throw conflict("La cardinalidad no es valida");
        String name = relation.name() == null ? "" : key(relation.name());
        return source.compareTo(target) <= 0
            ? List.of(source, relation.sourceCardinality().name(), target, relation.targetCardinality().name(), name)
            : List.of(target, relation.targetCardinality().name(), source, relation.sourceCardinality().name(), name);
    }
    private static boolean many(com.sw1.backend.ai.diagram.model.DiagramCardinality cardinality) {
        return cardinality == com.sw1.backend.ai.diagram.model.DiagramCardinality.ZERO_MANY
                || cardinality == com.sw1.backend.ai.diagram.model.DiagramCardinality.ONE_MANY;
    }
    private static Map<String, AttributeDefinition> requiredAttributes(
            Map<String, Map<String, AttributeDefinition>> entities, String entityName) {
        Map<String, AttributeDefinition> attributes = entities.get(key(entityName));
        if (attributes == null) throw conflict("La entidad destino no existe");
        return attributes;
    }
    private static RelationshipDefinition findRelationship(List<RelationshipDefinition> relationships,
            String source, String target, String name) {
        Set<String> endpoints = Set.of(key(source), key(target));
        List<RelationshipDefinition> matches = relationships.stream()
                .filter(relation -> Set.of(key(relation.sourceEntity()), key(relation.targetEntity())).equals(endpoints))
                .filter(relation -> name == null || Objects.equals(
                        relation.name() == null ? "" : key(relation.name()), key(name)))
                .toList();
        if (matches.isEmpty()) throw conflict("La relacion indicada no existe");
        if (matches.size() > 1) throw conflict("Existen varias relaciones candidatas; especifica su nombre");
        return matches.getFirst();
    }
    private static AiServiceException conflict(String message) {
        return new AiServiceException(HttpStatus.CONFLICT, message);
    }
}
