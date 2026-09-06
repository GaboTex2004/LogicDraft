package com.sw1.backend.ai.diagram.dto;

import com.sw1.backend.ai.diagram.model.DiagramDataType;
public record AttributeDefinition(String name, DiagramDataType dataType, boolean primaryKey, boolean nullable) {}
