package com.sw1.backend.ai.diagram.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.sw1.backend.ai.diagram.model.DiagramDataType;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record AttributeDefinition(
        String name,
        DiagramDataType dataType,
        boolean primaryKey,
        boolean nullable,
        String visibility,
        @JsonProperty("static") Boolean isStatic) {

    public AttributeDefinition(
            String name,
            DiagramDataType dataType,
            boolean primaryKey,
            boolean nullable) {
        this(name, dataType, primaryKey, nullable, null, null);
    }
}
