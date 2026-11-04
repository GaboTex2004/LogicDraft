package com.sw1.backend.ai.diagram.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record MethodDefinition(
        String name,
        String returnType,
        String visibility,
        @JsonProperty("static") Boolean isStatic,
        @JsonProperty("abstract") Boolean isAbstract,
        List<ParameterDefinition> parameters) {

    public MethodDefinition(
            String name,
            String returnType,
            String visibility,
            List<ParameterDefinition> parameters) {
        this(name, returnType, visibility, null, null, parameters);
    }

    public MethodDefinition(
            String name,
            String returnType,
            List<ParameterDefinition> parameters) {
        this(name, returnType, null, null, null, parameters);
    }
}
