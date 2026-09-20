package com.sw1.backend.generator.controller;

import com.sw1.backend.generator.schema.ApplicationSchema;
import com.sw1.backend.generator.service.ApplicationSchemaService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/proyectos/{projectId}/generator/schema")
public class ApplicationSchemaController {
    private final ApplicationSchemaService service;

    public ApplicationSchemaController(ApplicationSchemaService service) {
        this.service = service;
    }

    @GetMapping
    public ApplicationSchema preview(@PathVariable Long projectId) {
        return service.preview(projectId);
    }
}
