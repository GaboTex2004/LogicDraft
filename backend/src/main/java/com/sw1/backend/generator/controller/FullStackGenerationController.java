package com.sw1.backend.generator.controller;

import com.sw1.backend.generator.service.FullStackGenerationService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/proyectos/{projectId}/generator")
public class FullStackGenerationController {
    private static final MediaType ZIP = MediaType.parseMediaType("application/zip");
    private final FullStackGenerationService service;

    public FullStackGenerationController(FullStackGenerationService service) {
        this.service = service;
    }

    @PostMapping("/fullstack")
    public ResponseEntity<byte[]> generate(@PathVariable Long projectId) {
        var project = service.generate(projectId);
        return ResponseEntity.ok()
                .contentType(ZIP)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + project.fileName() + "\"")
                .contentLength(project.content().length)
                .body(project.content());
    }
}
