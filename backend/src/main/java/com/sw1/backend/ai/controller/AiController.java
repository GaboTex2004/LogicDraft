package com.sw1.backend.ai.controller;

import com.sw1.backend.ai.dto.request.AiGenerateRequest;
import com.sw1.backend.ai.dto.response.AiGenerateResponse;
import com.sw1.backend.ai.service.AiService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ai")
public class AiController {
    private final AiService service;

    public AiController(AiService service) {
        this.service = service;
    }

    @PostMapping("/generate")
    public AiGenerateResponse generate(@Valid @RequestBody AiGenerateRequest request) {
        return service.generate(request);
    }

    @PostMapping("/diagram/interpret")
    public com.sw1.backend.ai.diagram.dto.DiagramInterpretResponse interpret(@Valid @RequestBody AiGenerateRequest request) {
        return service.interpret(request);
    }
}
