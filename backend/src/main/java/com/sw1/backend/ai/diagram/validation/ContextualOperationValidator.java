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
        for (RelationshipDefinition relation : context.relationships()) relationships.add(relationKey(relation));
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
            }
            result.add(operation);
        }
        return new DiagramInterpretResponse(List.copyOf(result));
    }

    private static List<String> relationKey(RelationshipDefinition relation) {
        String source = key(relation.sourceEntity()), target = key(relation.targetEntity());
        if (relation.sourceCardinality() == null || relation.targetCardinality() == null)
            throw conflict("La cardinalidad no es valida");
        return source.compareTo(target) <= 0
            ? List.of(source, relation.sourceCardinality().name(), target, relation.targetCardinality().name())
            : List.of(target, relation.targetCardinality().name(), source, relation.sourceCardinality().name());
    }
    private static AiServiceException conflict(String message) {
        return new AiServiceException(HttpStatus.CONFLICT, message);
    }
}
