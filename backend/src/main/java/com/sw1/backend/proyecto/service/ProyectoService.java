package com.sw1.backend.proyecto.service;

import com.sw1.backend.common.exception.RecursoNoEncontradoException;
import com.sw1.backend.proyecto.dto.request.ActualizarProyectoRequest;
import com.sw1.backend.proyecto.dto.request.CrearProyectoRequest;
import com.sw1.backend.proyecto.dto.response.ProyectoResponse;
import com.sw1.backend.proyecto.model.Proyecto;
import com.sw1.backend.proyecto.repository.ProyectoRepository;
import com.sw1.backend.workspace.model.Workspace;
import com.sw1.backend.workspace.model.RolWorkspace;
import com.sw1.backend.workspace.service.WorkspaceAccessService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Transactional
public class ProyectoService {

    private final ProyectoRepository proyectoRepository;
    private final WorkspaceAccessService workspaceAccessService;

    public ProyectoService(
            ProyectoRepository proyectoRepository,
            WorkspaceAccessService workspaceAccessService) {
        this.proyectoRepository = proyectoRepository;
        this.workspaceAccessService = workspaceAccessService;
    }

    @Transactional(readOnly = true)
    public List<ProyectoResponse> listarPorWorkspace(Long workspaceId) {
        workspaceAccessService.verificarAcceso(workspaceId);

        return proyectoRepository.findByWorkspaceId(workspaceId).stream()
                .map(this::convertirAResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public ProyectoResponse buscarPorId(Long id) {
        Proyecto proyecto = buscarEntidadPorId(id);
        workspaceAccessService.verificarAcceso(
                proyecto.getWorkspace().getId());
        return convertirAResponse(proyecto);
    }

    public ProyectoResponse crear(CrearProyectoRequest request) {
        workspaceAccessService.verificarRol(
                request.getWorkspaceId(),
                RolWorkspace.OWNER,
                RolWorkspace.EDITOR);
        Workspace workspace = workspaceAccessService
                .obtenerWorkspaceConAcceso(request.getWorkspaceId());

        Proyecto proyecto = new Proyecto();
        proyecto.setNombre(request.getNombre());
        proyecto.setDescripcion(request.getDescripcion());
        proyecto.setWorkspace(workspace);

        return convertirAResponse(proyectoRepository.save(proyecto));
    }

    public ProyectoResponse actualizar(
            Long id,
            ActualizarProyectoRequest request) {
        Proyecto proyectoExistente = buscarEntidadPorId(id);
        Long workspaceId = proyectoExistente.getWorkspace().getId();
        workspaceAccessService.verificarRol(
                workspaceId,
                RolWorkspace.OWNER,
                RolWorkspace.EDITOR);

        proyectoExistente.setNombre(request.getNombre());
        proyectoExistente.setDescripcion(request.getDescripcion());

        return convertirAResponse(proyectoRepository.save(proyectoExistente));
    }

    public void eliminar(Long id) {
        Proyecto proyecto = buscarEntidadPorId(id);
        workspaceAccessService.verificarRol(
                proyecto.getWorkspace().getId(),
                RolWorkspace.OWNER,
                RolWorkspace.EDITOR);
        proyectoRepository.delete(proyecto);
    }

    private Proyecto buscarEntidadPorId(Long id) {
        return proyectoRepository.findById(id)
                .orElseThrow(() -> new RecursoNoEncontradoException(
                        "No existe un proyecto con el id " + id));
    }

    private ProyectoResponse convertirAResponse(Proyecto proyecto) {
        ProyectoResponse response = new ProyectoResponse();
        response.setId(proyecto.getId());
        response.setNombre(proyecto.getNombre());
        response.setDescripcion(proyecto.getDescripcion());
        response.setFechaCreacion(proyecto.getFechaCreacion());
        response.setWorkspaceId(proyecto.getWorkspace().getId());
        response.setWorkspaceNombre(proyecto.getWorkspace().getNombre());
        return response;
    }
}
