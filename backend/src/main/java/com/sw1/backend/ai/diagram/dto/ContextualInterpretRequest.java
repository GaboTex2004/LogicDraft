package com.sw1.backend.ai.diagram.dto;

public record ContextualInterpretRequest(String prompt, DiagramContext diagram, DiagramSelection selection) {
    public ContextualInterpretRequest(String prompt, DiagramContext diagram) { this(prompt, diagram, null); }
}
