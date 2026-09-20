package com.sw1.backend.ai.controller;

import com.sw1.backend.ai.diagram.dto.DiagramInterpretResponse;
import com.sw1.backend.ai.service.ImageInterpretationService;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/proyectos/{projectId}/ai/image")
public class ProjectImageController {

    private final ImageInterpretationService service;

    public ProjectImageController(ImageInterpretationService service) {
        this.service = service;
    }

    @PostMapping(
            value = "/interpret",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    public DiagramInterpretResponse interpret(
            @PathVariable Long projectId,
            @RequestPart("image") MultipartFile image,
            @RequestParam(value = "prompt", required = false, defaultValue = "") String prompt
    ) {
        return service.interpret(projectId, image, prompt);
    }
}