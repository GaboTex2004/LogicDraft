package com.sw1.backend.ai.diagram.dto;

import java.util.List;

public record AssociationCreationDefinition(String sourceEntity, String targetEntity,
        String associationEntityName, List<AttributeDefinition> attributes) {}
