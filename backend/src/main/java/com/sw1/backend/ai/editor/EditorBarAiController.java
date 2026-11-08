package com.sw1.backend.ai.editor;

import com.sw1.backend.ai.agent.dto.AgentAskRequest;
import com.sw1.backend.ai.agent.dto.AgentAskResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/proyectos/{projectId}/ai/diagram")
public class EditorBarAiController {
    private final EditorBarAiService service;
    public EditorBarAiController(EditorBarAiService service) { this.service = service; }

    @PostMapping("/assist")
    public AgentAskResponse assist(@PathVariable Long projectId, @Valid @RequestBody AgentAskRequest request) {
        return service.assist(projectId, request);
    }
}
