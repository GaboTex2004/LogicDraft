package com.sw1.backend.generator.schema;

import java.util.List;

public record ApplicationAssociation(
        AssociationKind kind,
        String tableName,
        List<ApplicationAssociationEndpoint> endpoints,
        boolean uniquePair) {
}
