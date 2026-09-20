package com.sw1.backend.generator.spring;

import com.sw1.backend.generator.schema.ApplicationEntity;
import com.sw1.backend.generator.schema.ApplicationRelationship;

public record OwnedRelation(
        Kind kind,
        ApplicationRelationship relationship,
        ApplicationEntity owner,
        ApplicationEntity target,
        String propertyName,
        String dtoPropertyName,
        boolean optional,
        String joinColumnName) {
    public enum Kind { MANY_TO_ONE, ONE_TO_ONE, MANY_TO_MANY }
}
