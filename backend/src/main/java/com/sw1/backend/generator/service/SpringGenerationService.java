package com.sw1.backend.generator.service;

import com.sw1.backend.common.exception.RecursoNoEncontradoException;
import com.sw1.backend.generator.spring.SpringBootGenerator;
import com.sw1.backend.generator.zip.SafeZipWriter;
import com.sw1.backend.proyecto.repository.ProyectoRepository;
import com.sw1.backend.workspace.model.RolWorkspace;
import com.sw1.backend.workspace.service.WorkspaceAccessService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class SpringGenerationService {
    private final ProyectoRepository projects;
    private final WorkspaceAccessService access;
    private final ApplicationSchemaService schemas;
    private final SpringBootGenerator generator;

    public SpringGenerationService(ProyectoRepository projects, WorkspaceAccessService access,
                                   ApplicationSchemaService schemas, SpringBootGenerator generator) {
        this.projects = projects;
        this.access = access;
        this.schemas = schemas;
        this.generator = generator;
    }

    public GeneratedBackend generate(Long projectId) {
        var project = projects.findById(projectId)
                .orElseThrow(() -> new RecursoNoEncontradoException("El proyecto no existe"));
        access.verificarRol(project.getWorkspace().getId(), RolWorkspace.OWNER, RolWorkspace.EDITOR);
        var generated = generator.generate(schemas.preview(projectId));
        return new GeneratedBackend(generated.downloadFileName(), SafeZipWriter.write(generated));
    }
}
