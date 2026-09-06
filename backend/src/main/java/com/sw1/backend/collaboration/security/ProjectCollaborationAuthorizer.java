package com.sw1.backend.collaboration.security;

import com.sw1.backend.common.exception.AccesoDenegadoException;
import com.sw1.backend.common.exception.RecursoNoEncontradoException;
import com.sw1.backend.proyecto.model.Proyecto;
import com.sw1.backend.proyecto.repository.ProyectoRepository;
import com.sw1.backend.tenant.repository.MiembroTenantRepository;
import com.sw1.backend.usuario.model.Usuario;
import com.sw1.backend.usuario.repository.UsuarioRepository;
import com.sw1.backend.workspace.repository.MiembroWorkspaceRepository;
import com.sw1.backend.workspace.model.MiembroWorkspace;
import com.sw1.backend.workspace.model.RolWorkspace;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.Principal;

@Service
@Transactional(readOnly = true)
public class ProjectCollaborationAuthorizer {
    private final UsuarioRepository usuarioRepository;
    private final ProyectoRepository proyectoRepository;
    private final MiembroTenantRepository miembroTenantRepository;
    private final MiembroWorkspaceRepository miembroWorkspaceRepository;

    public ProjectCollaborationAuthorizer(
            UsuarioRepository usuarioRepository,
            ProyectoRepository proyectoRepository,
            MiembroTenantRepository miembroTenantRepository,
            MiembroWorkspaceRepository miembroWorkspaceRepository) {
        this.usuarioRepository = usuarioRepository;
        this.proyectoRepository = proyectoRepository;
        this.miembroTenantRepository = miembroTenantRepository;
        this.miembroWorkspaceRepository = miembroWorkspaceRepository;
    }

    public CollaborationIdentity authorize(Long projectId, Principal principal) {
        if (principal == null || principal.getName() == null) {
            throw new AccesoDenegadoException("No existe un usuario autenticado para la colaboración");
        }
        Usuario usuario = usuarioRepository.findByEmail(principal.getName())
                .orElseThrow(() -> new AccesoDenegadoException("El usuario autenticado ya no existe"));
        Proyecto proyecto = proyectoRepository.findById(projectId)
                .orElseThrow(() -> new RecursoNoEncontradoException(
                        "No existe un proyecto con el id " + projectId));
        Long workspaceId = proyecto.getWorkspace().getId();
        Long tenantId = proyecto.getWorkspace().getTenant().getId();

        if (miembroTenantRepository.findByUsuarioIdAndTenantId(usuario.getId(), tenantId).isEmpty()
                || miembroWorkspaceRepository.findByUsuarioIdAndWorkspaceId(usuario.getId(), workspaceId).isEmpty()) {
            throw new AccesoDenegadoException("No tienes acceso al proyecto solicitado");
        }
        return new CollaborationIdentity(usuario.getId(), usuario.getEmail(), usuario.getNombre());
    }

    public CollaborationIdentity authorizeEditor(Long projectId, Principal principal) {
        CollaborationIdentity identity = authorize(projectId, principal);
        Proyecto proyecto = proyectoRepository.findById(projectId)
                .orElseThrow(() -> new RecursoNoEncontradoException(
                        "No existe un proyecto con el id " + projectId));
        MiembroWorkspace membership = miembroWorkspaceRepository
                .findByUsuarioIdAndWorkspaceId(identity.userId(), proyecto.getWorkspace().getId())
                .orElseThrow(() -> new AccesoDenegadoException("No tienes acceso al proyecto solicitado"));
        if (membership.getRol() != RolWorkspace.OWNER && membership.getRol() != RolWorkspace.EDITOR) {
            throw new AccesoDenegadoException("No tienes permiso para modificar este proyecto");
        }
        return identity;
    }
}
