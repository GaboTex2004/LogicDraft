package com.sw1.backend.ai.diagram.dto;

import java.util.List;
public record EntityDefinition(String name, List<AttributeDefinition> attributes) {}
