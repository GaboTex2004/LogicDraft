package com.sw1.backend.ai.controller;

import com.sw1.backend.ai.diagram.dto.DiagramInterpretResponse;
import com.sw1.backend.ai.dto.request.AiGenerateRequest;
import com.sw1.backend.ai.service.ContextualDiagramAiService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/proyectos/{projectId}/ai/diagram")
public class ProjectDiagramAiController {
    private final ContextualDiagramAiService service;
    public ProjectDiagramAiController(ContextualDiagramAiService service) { this.service = service; }

    @PostMapping("/interpret")
    public DiagramInterpretResponse interpret(@PathVariable Long projectId, @Valid @RequestBody AiGenerateRequest request) {
        return service.interpret(projectId, request);
    }
}
