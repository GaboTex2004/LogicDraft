package com.sw1.backend.generator.service;

import com.sw1.backend.common.exception.RecursoNoEncontradoException;
import com.sw1.backend.diagrama.repository.DiagramaRepository;
import com.sw1.backend.generator.mapper.ApplicationSchemaMapper;
import com.sw1.backend.generator.schema.ApplicationSchema;
import com.sw1.backend.proyecto.repository.ProyectoRepository;
import com.sw1.backend.workspace.service.WorkspaceAccessService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class ApplicationSchemaService {
    private final ProyectoRepository projects;
    private final DiagramaRepository diagrams;
    private final WorkspaceAccessService access;

    public ApplicationSchemaService(ProyectoRepository projects, DiagramaRepository diagrams,
                                    WorkspaceAccessService access) {
        this.projects = projects;
        this.diagrams = diagrams;
        this.access = access;
    }

    public ApplicationSchema preview(Long projectId) {
        var project = projects.findById(projectId)
                .orElseThrow(() -> new RecursoNoEncontradoException("El proyecto no existe"));
        access.verificarAcceso(project.getWorkspace().getId());
        var diagram = diagrams.findByProyectoId(projectId)
                .orElseThrow(() -> new RecursoNoEncontradoException("El proyecto todavia no tiene un diagrama guardado"));
        return ApplicationSchemaMapper.fromDocument(project.getNombre(), diagram.getContenido());
    }
}
