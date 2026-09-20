package com.sw1.backend.generator.schema;

public record ApplicationAssociationEndpoint(
        AssociationEndpointRole role,
        String entityId,
        String relationshipId,
        String foreignKeyName) {
}
