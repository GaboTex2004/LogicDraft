package com.sw1.backend.ai.diagram.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ParameterDefinition(String name, String type) {}
