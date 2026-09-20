package com.sw1.backend.generator.ea;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/proyectos/{proyectoId}/export")
public class EnterpriseArchitectExportController {

    private final EnterpriseArchitectExportSourceService sourceService;
    private final EnterpriseArchitectXmiGenerator generator;

    public EnterpriseArchitectExportController(
            EnterpriseArchitectExportSourceService sourceService,
            EnterpriseArchitectXmiGenerator generator
    ) {
        this.sourceService = sourceService;
        this.generator = generator;
    }

    @GetMapping("/enterprise-architect")
    public ResponseEntity<byte[]> export(
            @PathVariable Long proyectoId
    ) {
        // Recupera el proyecto y comprueba los permisos de acceso.
        var snapshot = sourceService.load(proyectoId);

        // Genera el archivo sin modificar el proyecto.
        byte[] xmi = generator.generate(snapshot);

        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_XML)
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"logicdraft-enterprise-architect.xmi\""
                )
                .contentLength(xmi.length)
                .body(xmi);
    }
}