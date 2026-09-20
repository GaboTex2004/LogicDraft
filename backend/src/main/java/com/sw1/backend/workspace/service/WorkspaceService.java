package com.sw1.backend.workspace.service;

import com.sw1.backend.auth.service.UsuarioActualService;
import com.sw1.backend.common.exception.AccesoDenegadoException;
import com.sw1.backend.tenant.model.MiembroTenant;
import com.sw1.backend.tenant.model.RolTenant;
import com.sw1.backend.tenant.repository.MiembroTenantRepository;
import com.sw1.backend.usuario.model.Usuario;
import com.sw1.backend.workspace.dto.request.CrearWorkspaceRequest;
import com.sw1.backend.workspace.dto.response.WorkspaceResponse;
import com.sw1.backend.workspace.model.MiembroWorkspace;
import com.sw1.backend.workspace.model.RolWorkspace;
import com.sw1.backend.workspace.model.Workspace;
import com.sw1.backend.workspace.repository.MiembroWorkspaceRepository;
import com.sw1.backend.workspace.repository.WorkspaceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Transactional(readOnly = true)
public class WorkspaceService {

    private final UsuarioActualService usuarioActualService;
    private final MiembroTenantRepository miembroTenantRepository;
    private final MiembroWorkspaceRepository miembroWorkspaceRepository;
    private final WorkspaceRepository workspaceRepository;

    public WorkspaceService(
            UsuarioActualService usuarioActualService,
            MiembroTenantRepository miembroTenantRepository,
            MiembroWorkspaceRepository miembroWorkspaceRepository,
            WorkspaceRepository workspaceRepository) {

        this.usuarioActualService = usuarioActualService;
        this.miembroTenantRepository = miembroTenantRepository;
        this.miembroWorkspaceRepository = miembroWorkspaceRepository;
        this.workspaceRepository = workspaceRepository;
    }

    public List<WorkspaceResponse> listarMisWorkspaces() {

        Usuario usuario = usuarioActualService.obtenerUsuarioActual();

        return miembroWorkspaceRepository
                .findByUsuarioId(usuario.getId())
                .stream()
                .map(this::convertirAResponse)
                .toList();
    }

    @Transactional
    public WorkspaceResponse crearWorkspace(CrearWorkspaceRequest request) {

        Usuario usuario = usuarioActualService.obtenerUsuarioActual();

        List<MiembroTenant> membresias =
                miembroTenantRepository.findByUsuarioId(usuario.getId());

        if (membresias.size() != 1) {
            throw new AccesoDenegadoException(
                    "No se puede determinar un único tenant para crear el workspace"
            );
        }

        MiembroTenant membresiaTenant = membresias.get(0);

        if (membresiaTenant.getRol() != RolTenant.OWNER) {
            throw new AccesoDenegadoException(
                    "No tienes permiso para crear workspaces en este tenant"
            );
        }

        Workspace workspace = new Workspace();
        workspace.setNombre(request.nombre().trim());
        workspace.setTenant(membresiaTenant.getTenant());

        Workspace workspaceGuardado =
                workspaceRepository.save(workspace);

        MiembroWorkspace miembro = new MiembroWorkspace();
        miembro.setUsuario(usuario);
        miembro.setWorkspace(workspaceGuardado);
        miembro.setRol(RolWorkspace.OWNER);

        MiembroWorkspace miembroGuardado =
                miembroWorkspaceRepository.save(miembro);

        return convertirAResponse(miembroGuardado);
    }

    private WorkspaceResponse convertirAResponse(
        MiembroWorkspace miembro) {

        return new WorkspaceResponse(
                miembro.getWorkspace().getId(),
                miembro.getWorkspace().getNombre(),
                miembro.getWorkspace().getTenant().getId(),
                miembro.getRol().name()
        );
    }
}