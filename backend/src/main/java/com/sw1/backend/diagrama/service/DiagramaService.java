package com.sw1.backend.diagrama.service;

import com.sw1.backend.common.exception.RecursoNoEncontradoException;
import com.sw1.backend.diagrama.dto.request.GuardarDiagramaRequest;
import com.sw1.backend.diagrama.dto.response.DiagramaResponse;
import com.sw1.backend.diagrama.model.Diagrama;
import com.sw1.backend.diagrama.repository.DiagramaRepository;
import com.sw1.backend.proyecto.model.Proyecto;
import com.sw1.backend.proyecto.repository.ProyectoRepository;
import com.sw1.backend.workspace.model.RolWorkspace;
import com.sw1.backend.workspace.service.WorkspaceAccessService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
@Transactional
public class DiagramaService {
    static final int VERSION_SOPORTADA = 1;
    static final int TAMANO_MAXIMO_BYTES = 1_000_000;
    private final DiagramaRepository diagramaRepository;
    private final ProyectoRepository proyectoRepository;
    private final WorkspaceAccessService workspaceAccessService;
    public DiagramaService(DiagramaRepository diagramaRepository, ProyectoRepository proyectoRepository, WorkspaceAccessService workspaceAccessService) {
        this.diagramaRepository = diagramaRepository;
        this.proyectoRepository = proyectoRepository;
        this.workspaceAccessService = workspaceAccessService;
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
        Diagrama diagrama = diagramaRepository.findByProyectoId(proyectoId).orElseGet(Diagrama::new);
        if (diagrama.getId() == null) diagrama.setProyecto(proyecto);
        diagrama.setVersion(request.getVersion());
        diagrama.setContenido(contenido);
        return convertirAResponse(diagramaRepository.save(diagrama));
    }
    private Map<String, Object> crearYValidarContenido(GuardarDiagramaRequest request) {
        if (!Integer.valueOf(VERSION_SOPORTADA).equals(request.getVersion())) throw new IllegalArgumentException("La version del documento no esta soportada");
        Map<String, Object> contenido = new LinkedHashMap<>();
        contenido.put("version", request.getVersion());
        contenido.put("nodes", request.getNodes());
        contenido.put("edges", request.getEdges());
        if (contenido.toString().getBytes(StandardCharsets.UTF_8).length > TAMANO_MAXIMO_BYTES) throw new IllegalArgumentException("El documento supera el tamano maximo permitido");
        return contenido;
    }
    private DiagramaResponse convertirAResponse(Diagrama diagrama) {
        return new DiagramaResponse(diagrama.getId(), diagrama.getProyecto().getId(), diagrama.getVersion(), diagrama.getContenido(), diagrama.getFechaActualizacion());
    }

    private Proyecto obtenerProyecto(Long proyectoId) {
        return proyectoRepository.findById(proyectoId)
                .orElseThrow(() -> new RecursoNoEncontradoException("No existe un proyecto con el id " + proyectoId));
    }
}
