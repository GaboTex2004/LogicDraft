package com.sw1.backend.ai.diagram.dto;

import java.util.List;

public record DiagramAssociationContext(
        String entityName,
        String tableName,
        List<String> endpointEntityNames,
        List<String> structuralRelationshipIds) {}
