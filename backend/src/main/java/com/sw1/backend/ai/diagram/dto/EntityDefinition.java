package com.sw1.backend.ai.diagram.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record EntityDefinition(
        String name,
        List<AttributeDefinition> attributes,
        String kind,
        @JsonProperty("abstract") Boolean isAbstract,
        List<MethodDefinition> methods,
        List<String> literals) {

    public EntityDefinition(String name, List<AttributeDefinition> attributes) {
        this(name, attributes, null, null, null, null);
    }

    public EntityDefinition(String name, List<AttributeDefinition> attributes, String kind) {
        this(name, attributes, kind, null, null, null);
    }
}
