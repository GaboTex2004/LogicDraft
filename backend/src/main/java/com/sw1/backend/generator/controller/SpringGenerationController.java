package com.sw1.backend.generator.controller;

import com.sw1.backend.generator.service.SpringGenerationService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/proyectos/{projectId}/generator")
public class SpringGenerationController {
    private static final MediaType ZIP = MediaType.parseMediaType("application/zip");
    private final SpringGenerationService service;

    public SpringGenerationController(SpringGenerationService service) {
        this.service = service;
    }

    @PostMapping("/backend")
    public ResponseEntity<byte[]> generate(@PathVariable Long projectId) {
        var backend = service.generate(projectId);
        return ResponseEntity.ok()
                .contentType(ZIP)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + backend.fileName() + "\"")
                .contentLength(backend.content().length)
                .body(backend.content());
    }
}
