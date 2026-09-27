package com.sw1.backend.ai.diagram.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record DiagramSelection(String kind, String entityName, String sourceEntity,
        String targetEntity, String relationshipName) {}
