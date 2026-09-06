package com.sw1.backend.workspace.service;

import com.sw1.backend.auth.service.UsuarioActualService;
import com.sw1.backend.common.exception.AccesoDenegadoException;
import com.sw1.backend.common.exception.RecursoNoEncontradoException;
import com.sw1.backend.tenant.service.TenantAccessService;
import com.sw1.backend.usuario.model.Usuario;
import com.sw1.backend.workspace.model.MiembroWorkspace;
import com.sw1.backend.workspace.model.RolWorkspace;
import com.sw1.backend.workspace.model.Workspace;
import com.sw1.backend.workspace.repository.MiembroWorkspaceRepository;
import com.sw1.backend.workspace.repository.WorkspaceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;

@Service
@Transactional(readOnly = true)
public class WorkspaceAccessService {

    private final UsuarioActualService usuarioActualService;
    private final TenantAccessService tenantAccessService;
    private final WorkspaceRepository workspaceRepository;
    private final MiembroWorkspaceRepository miembroWorkspaceRepository;

    public WorkspaceAccessService(
            UsuarioActualService usuarioActualService,
            TenantAccessService tenantAccessService,
            WorkspaceRepository workspaceRepository,
            MiembroWorkspaceRepository miembroWorkspaceRepository) {
        this.usuarioActualService = usuarioActualService;
        this.tenantAccessService = tenantAccessService;
        this.workspaceRepository = workspaceRepository;
        this.miembroWorkspaceRepository = miembroWorkspaceRepository;
    }

    public Workspace obtenerWorkspaceConAcceso(Long workspaceId) {
        Usuario usuario = usuarioActualService.obtenerUsuarioActual();
        Workspace workspace = obtenerWorkspaceYVerificarTenant(workspaceId);
        obtenerMembresia(usuario.getId(), workspace.getId());
        return workspace;
    }

    public MiembroWorkspace obtenerMembresia(Long workspaceId) {
        Usuario usuario = usuarioActualService.obtenerUsuarioActual();
        Workspace workspace = obtenerWorkspaceYVerificarTenant(workspaceId);
        return obtenerMembresia(usuario.getId(), workspace.getId());
    }

    public void verificarAcceso(Long workspaceId) {
        obtenerMembresia(workspaceId);
    }

    public void verificarRol(
            Long workspaceId,
            RolWorkspace... rolesPermitidos) {
        MiembroWorkspace miembro = obtenerMembresia(workspaceId);
        boolean rolPermitido = rolesPermitidos != null
                && Arrays.asList(rolesPermitidos).contains(miembro.getRol());

        if (!rolPermitido) {
            throw new AccesoDenegadoException(
                    "No tienes el rol requerido dentro del workspace");
        }
    }

    private Workspace obtenerWorkspaceYVerificarTenant(Long workspaceId) {
        Workspace workspace = workspaceRepository.findById(workspaceId)
                .orElseThrow(() -> new RecursoNoEncontradoException(
                        "No existe un workspace con el id " + workspaceId));

        tenantAccessService.verificarAcceso(workspace.getTenant().getId());
        return workspace;
    }

    private MiembroWorkspace obtenerMembresia(
            Long usuarioId,
            Long workspaceId) {
        return miembroWorkspaceRepository
                .findByUsuarioIdAndWorkspaceId(usuarioId, workspaceId)
                .orElseThrow(() -> new AccesoDenegadoException(
                        "No tienes acceso al workspace solicitado"));
    }
}
