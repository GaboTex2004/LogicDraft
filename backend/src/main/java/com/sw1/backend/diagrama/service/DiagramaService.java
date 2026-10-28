package com.sw1.backend.diagrama.service;

import com.sw1.backend.common.exception.RecursoNoEncontradoException;
import com.sw1.backend.collaboration.service.DiagramCommitPublisher;
import com.sw1.backend.diagrama.dto.request.GuardarDiagramaRequest;
import com.sw1.backend.diagrama.dto.response.DiagramaResponse;
import com.sw1.backend.diagrama.model.Diagrama;
import com.sw1.backend.diagrama.repository.DiagramaRepository;
import com.sw1.backend.diagrama.validation.DiagramDocumentValidator;
import com.sw1.backend.proyecto.model.Proyecto;
import com.sw1.backend.proyecto.repository.ProyectoRepository;
import com.sw1.backend.workspace.model.RolWorkspace;
import com.sw1.backend.workspace.service.WorkspaceAccessService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

@Service
@Transactional
public class DiagramaService {
    static final Set<Integer> VERSIONES_SOPORTADAS = Set.of(1, 2);
    static final int TAMANO_MAXIMO_BYTES = 1_000_000;
    private final DiagramaRepository diagramaRepository;
    private final ProyectoRepository proyectoRepository;
    private final WorkspaceAccessService workspaceAccessService;
    private final DiagramCommitPublisher commitPublisher;
    public DiagramaService(DiagramaRepository diagramaRepository, ProyectoRepository proyectoRepository,
                           WorkspaceAccessService workspaceAccessService, DiagramCommitPublisher commitPublisher) {
        this.diagramaRepository = diagramaRepository;
        this.proyectoRepository = proyectoRepository;
        this.workspaceAccessService = workspaceAccessService;
        this.commitPublisher = commitPublisher;
    }
    @Transactional(readOnly = true)
    public DiagramaResponse obtenerPorProyecto(Long proyectoId) {
        Proyecto proyecto = obtenerProyecto(proyectoId);
        workspaceAccessService.verificarAcceso(proyecto.getWorkspace().getId());
        Diagrama diagrama = diagramaRepository.findByProyectoId(proyectoId)
                .orElseThrow(() -> new RecursoNoEncontradoException("El proyecto todavia no tiene un diagrama guardado"));
        return convertirAResponse(diagrama);
    }
    public DiagramaResponse guardar(Long proyectoId, GuardarDiagramaRequest request) {
        Proyecto proyecto = obtenerProyecto(proyectoId);
        workspaceAccessService.verificarRol(proyecto.getWorkspace().getId(), RolWorkspace.OWNER, RolWorkspace.EDITOR);
        Map<String, Object> contenido = crearYValidarContenido(request);
        Diagrama diagrama = diagramaRepository.findByProyectoId(proyectoId).orElse(null);
        if (diagrama == null) {
            if (request.getBaseRevision() != null) throw new DiagramConflictException(proyectoId, null);
            diagrama = new Diagrama();
            diagrama.setProyecto(proyecto);
        } else if (request.getBaseRevision() == null
                || !request.getBaseRevision().equals(diagrama.getRevision())) {
            throw new DiagramConflictException(proyectoId, diagrama.getRevision());
        }
        diagrama.setVersion(request.getVersion());
        diagrama.setContenido(contenido);
        DiagramaResponse saved;
        try {
            saved = convertirAResponse(diagramaRepository.saveAndFlush(diagrama));
        } catch (OptimisticLockingFailureException | DataIntegrityViolationException exception) {
            throw new DiagramConflictException(proyectoId, null);
        }
        commitPublisher.schedule(saved, request.getClientId());
        return saved;
    }
    private Map<String, Object> crearYValidarContenido(GuardarDiagramaRequest request) {
        if (!VERSIONES_SOPORTADAS.contains(request.getVersion())) throw new IllegalArgumentException("La version del documento no esta soportada");
        Map<String, Object> contenido = new LinkedHashMap<>();
        contenido.put("version", request.getVersion());
        contenido.put("nodes", request.getNodes());
        contenido.put("edges", request.getEdges());
        if (contenido.toString().getBytes(StandardCharsets.UTF_8).length > TAMANO_MAXIMO_BYTES) throw new IllegalArgumentException("El documento supera el tamano maximo permitido");
        DiagramDocumentValidator.validate(contenido);
        return contenido;
    }
    private DiagramaResponse convertirAResponse(Diagrama diagrama) {
        return new DiagramaResponse(diagrama.getId(), diagrama.getProyecto().getId(), diagrama.getVersion(),
                diagrama.getRevision(), diagrama.getContenido(), diagrama.getFechaActualizacion());
    }

    private Proyecto obtenerProyecto(Long proyectoId) {
        return proyectoRepository.findById(proyectoId)
                .orElseThrow(() -> new RecursoNoEncontradoException("No existe un proyecto con el id " + proyectoId));
    }
}
