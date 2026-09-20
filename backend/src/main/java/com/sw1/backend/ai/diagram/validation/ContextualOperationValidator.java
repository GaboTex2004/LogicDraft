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
                case ADD_RELATIONSHIP -> {
                    RelationshipDefinition relation = operation.relationship();
                    if (!entities.containsKey(key(relation.sourceEntity())) || !entities.containsKey(key(relation.targetEntity())))
                        throw conflict("La relacion referencia una entidad inexistente");
                    if (key(relation.sourceEntity()).equals(key(relation.targetEntity())))
                        throw conflict("Las autorrelaciones nuevas no estan habilitadas en este MVP");
                    if (relation.sourceCardinality() == null || relation.targetCardinality() == null)
                        throw conflict("La cardinalidad propuesta no es valida");
                    if (!relationships.add(relationKey(relation))) continue;
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
    private static AiServiceException conflict(String message) {
        return new AiServiceException(HttpStatus.CONFLICT, message);
    }
}
