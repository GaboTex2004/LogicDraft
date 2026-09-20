package com.sw1.backend.ai.diagram.dto;

import java.util.List;

public record AssociationConversionDefinition(
        String relationshipId,
        String sourceEntity,
        String targetEntity,
        String associationEntityName,
        List<AttributeDefinition> attributes) {}
