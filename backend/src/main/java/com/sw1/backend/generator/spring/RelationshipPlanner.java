package com.sw1.backend.generator.spring;

import com.sw1.backend.generator.schema.*;
import com.sw1.backend.generator.naming.TechnicalNameNormalizer;
import java.util.*;

public final class RelationshipPlanner {
    private RelationshipPlanner() {
    }

    public static Map<String, List<OwnedRelation>> plan(ApplicationSchema schema) {
        Map<String, ApplicationEntity> entities = new LinkedHashMap<>();
        schema.entities().forEach(entity -> entities.put(entity.id(), entity));
        Map<String, List<OwnedRelation>> result = new LinkedHashMap<>();
        schema.entities().forEach(entity -> result.put(entity.id(), new ArrayList<>()));
        for (ApplicationRelationship relationship : schema.relationships()) {
            ApplicationEntity source = entities.get(relationship.sourceEntityId());
            ApplicationEntity target = entities.get(relationship.targetEntityId());
            boolean sourceMany = many(relationship.sourceCardinality());
            boolean targetMany = many(relationship.targetCardinality());
            OwnedRelation relation;
            if (sourceMany && targetMany) {
                String property = relationship.name() == null
                        ? SpringNames.lowerFirst(target.technicalName()) + "Items"
                        : TechnicalNameNormalizer.fieldName(relationship.name());
                String dtoProperty = relationship.name() == null
                        ? SpringNames.lowerFirst(target.technicalName()) + "Ids"
                        : property + "Ids";
                relation = new OwnedRelation(OwnedRelation.Kind.MANY_TO_MANY, relationship, source, target,
                        property, dtoProperty,
                        relationship.targetCardinality() != ApplicationCardinality.ONE_MANY, null);
            } else if (sourceMany != targetMany) {
                ApplicationEntity owner = sourceMany ? source : target;
                ApplicationEntity one = sourceMany ? target : source;
                ApplicationCardinality oneCardinality = sourceMany
                        ? relationship.targetCardinality() : relationship.sourceCardinality();
                String property = SpringNames.lowerFirst(one.technicalName());
                ApplicationAssociationEndpoint associationEndpoint = associationEndpoint(owner, relationship.id());
                relation = new OwnedRelation(OwnedRelation.Kind.MANY_TO_ONE, relationship, owner, one,
                        property, property + "Id", oneCardinality == ApplicationCardinality.ZERO_ONE,
                        associationEndpoint == null ? null : associationEndpoint.foreignKeyName());
            } else {
                ApplicationEntity owner = target;
                String property = SpringNames.lowerFirst(source.technicalName());
                relation = new OwnedRelation(OwnedRelation.Kind.ONE_TO_ONE, relationship, owner, source,
                        property, property + "Id", relationship.sourceCardinality() == ApplicationCardinality.ZERO_ONE,
                        null);
            }
            result.get(relation.owner().id()).add(relation);
        }
        return result.entrySet().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                Map.Entry::getKey, entry -> List.copyOf(entry.getValue())));
    }

    private static boolean many(ApplicationCardinality value) {
        return value == ApplicationCardinality.ZERO_MANY || value == ApplicationCardinality.ONE_MANY;
    }

    private static ApplicationAssociationEndpoint associationEndpoint(ApplicationEntity owner, String relationshipId) {
        if (owner.association() == null || owner.association().endpoints() == null) return null;
        return owner.association().endpoints().stream()
                .filter(endpoint -> endpoint != null && relationshipId.equals(endpoint.relationshipId()))
                .findFirst().orElse(null);
    }
}
