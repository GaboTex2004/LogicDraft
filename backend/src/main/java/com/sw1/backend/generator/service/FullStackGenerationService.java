package com.sw1.backend.generator.service;

import com.sw1.backend.common.exception.RecursoNoEncontradoException;
import com.sw1.backend.generator.export.FullStackGenerator;
import com.sw1.backend.generator.zip.SafeZipWriter;
import com.sw1.backend.proyecto.repository.ProyectoRepository;
import com.sw1.backend.workspace.model.RolWorkspace;
import com.sw1.backend.workspace.service.WorkspaceAccessService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class FullStackGenerationService {
    private final ProyectoRepository projects;
    private final WorkspaceAccessService access;
    private final ApplicationSchemaService schemas;
    private final FullStackGenerator generator;

    public FullStackGenerationService(ProyectoRepository projects, WorkspaceAccessService access,
                                      ApplicationSchemaService schemas, FullStackGenerator generator) {
        this.projects = projects;
        this.access = access;
        this.schemas = schemas;
        this.generator = generator;
    }

    public GeneratedFullStack generate(Long projectId) {
        var project = projects.findById(projectId)
                .orElseThrow(() -> new RecursoNoEncontradoException("El proyecto no existe"));
        access.verificarRol(project.getWorkspace().getId(), RolWorkspace.OWNER, RolWorkspace.EDITOR);
        var schema = schemas.preview(projectId);
        var generated = generator.generate(schema);
        return new GeneratedFullStack(generated.downloadFileName(), SafeZipWriter.write(generated));
    }
}
