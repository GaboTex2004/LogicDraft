package com.sw1.backend.ai.agent.controller;

import com.sw1.backend.ai.agent.dto.*;
import com.sw1.backend.ai.agent.service.ContextualAgentService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/proyectos/{projectId}/agent")
public class ProjectAgentController {
    private final ContextualAgentService service;

    public ProjectAgentController(ContextualAgentService service) {
        this.service = service;
    }

    @PostMapping("/ask")
    public AgentAskResponse ask(@PathVariable Long projectId, @Valid @RequestBody AgentAskRequest request) {
        return service.ask(projectId, request);
    }
}
