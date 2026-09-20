package com.sw1.backend.generator.schema;

public record ApplicationRelationship(
        String id,
        String sourceEntityId,
        String targetEntityId,
        ApplicationCardinality sourceCardinality,
        ApplicationCardinality targetCardinality,
        String name,
        String joinTableName) {
    public ApplicationRelationship(String id, String sourceEntityId, String targetEntityId,
                                   ApplicationCardinality sourceCardinality,
                                   ApplicationCardinality targetCardinality) {
        this(id, sourceEntityId, targetEntityId, sourceCardinality, targetCardinality, null, null);
    }
}
