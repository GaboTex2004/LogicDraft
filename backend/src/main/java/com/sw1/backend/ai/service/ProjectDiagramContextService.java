package com.sw1.backend.ai.service;

import com.sw1.backend.ai.diagram.dto.DiagramContext;
import com.sw1.backend.ai.diagram.validation.DiagramContextMapper;
import com.sw1.backend.common.exception.RecursoNoEncontradoException;
import com.sw1.backend.diagrama.repository.DiagramaRepository;
import com.sw1.backend.proyecto.repository.ProyectoRepository;
import com.sw1.backend.workspace.model.RolWorkspace;
import com.sw1.backend.workspace.service.WorkspaceAccessService;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProjectDiagramContextService {
    private final ProyectoRepository projects;
    private final DiagramaRepository diagrams;
    private final WorkspaceAccessService access;

    public ProjectDiagramContextService(ProyectoRepository projects, DiagramaRepository diagrams, WorkspaceAccessService access) {
        this.projects = projects;
        this.diagrams = diagrams;
        this.access = access;
    }

    @Transactional(readOnly = true)
    public DiagramContext load(Long projectId) {
        var project = projects.findById(projectId)
                .orElseThrow(() -> new RecursoNoEncontradoException("El proyecto no existe"));
        access.verificarRol(project.getWorkspace().getId(), RolWorkspace.OWNER, RolWorkspace.EDITOR);
        return diagrams.findByProyectoId(projectId)
                .map(diagram -> DiagramContextMapper.fromDocument(diagram.getContenido()))
                .orElseGet(() -> new DiagramContext(List.of(), List.of()));
    }
}
