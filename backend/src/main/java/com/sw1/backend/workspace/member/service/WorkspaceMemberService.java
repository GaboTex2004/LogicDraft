package com.sw1.backend.workspace.member.service;

import com.sw1.backend.common.exception.AccesoDenegadoException;
import com.sw1.backend.common.exception.EmailDuplicadoException;
import com.sw1.backend.common.exception.RecursoNoEncontradoException;
import com.sw1.backend.tenant.model.MiembroTenant;
import com.sw1.backend.tenant.model.RolTenant;
import com.sw1.backend.tenant.repository.MiembroTenantRepository;
import com.sw1.backend.usuario.model.Usuario;
import com.sw1.backend.usuario.repository.UsuarioRepository;
import com.sw1.backend.workspace.member.dto.AgregarMiembroWorkspaceRequest;
import com.sw1.backend.workspace.member.dto.CambiarRolMiembroRequest;
import com.sw1.backend.workspace.member.dto.MiembroWorkspaceResponse;
import com.sw1.backend.workspace.model.MiembroWorkspace;
import com.sw1.backend.workspace.model.RolWorkspace;
import com.sw1.backend.workspace.model.Workspace;
import com.sw1.backend.workspace.repository.MiembroWorkspaceRepository;
import com.sw1.backend.workspace.service.WorkspaceAccessService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;

@Service
public class WorkspaceMemberService {
    private final WorkspaceAccessService workspaceAccessService;
    private final UsuarioRepository usuarioRepository;
    private final MiembroWorkspaceRepository miembroWorkspaceRepository;
    private final MiembroTenantRepository miembroTenantRepository;

    public WorkspaceMemberService(
            WorkspaceAccessService workspaceAccessService,
            UsuarioRepository usuarioRepository,
            MiembroWorkspaceRepository miembroWorkspaceRepository,
            MiembroTenantRepository miembroTenantRepository) {
        this.workspaceAccessService = workspaceAccessService;
        this.usuarioRepository = usuarioRepository;
        this.miembroWorkspaceRepository = miembroWorkspaceRepository;
        this.miembroTenantRepository = miembroTenantRepository;
    }

    @Transactional(readOnly = true)
    public List<MiembroWorkspaceResponse> list(Long workspaceId) {
        Workspace workspace = workspaceAccessService.obtenerWorkspaceConAcceso(workspaceId);
        return miembroWorkspaceRepository.findByWorkspace(workspace).stream()
                .sorted(Comparator.comparing(member -> member.getRol() != RolWorkspace.OWNER))
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public MiembroWorkspaceResponse add(Long workspaceId, AgregarMiembroWorkspaceRequest request) {
        Workspace workspace = requireOwner(workspaceId);
        if (request.rol() == RolWorkspace.OWNER) {
            throw new AccesoDenegadoException("No se puede agregar otro OWNER desde esta operación");
        }
        String email = request.email().trim();
        Usuario user = usuarioRepository.findByEmail(email)
                .orElseThrow(() -> new RecursoNoEncontradoException(
                        "No existe una cuenta registrada con el email indicado"));
        if (miembroWorkspaceRepository.existsByUsuarioAndWorkspace(user, workspace)) {
            throw new EmailDuplicadoException("El usuario ya pertenece a este workspace");
        }

        miembroTenantRepository.findByUsuarioAndTenant(user, workspace.getTenant())
                .orElseGet(() -> createTenantMember(user, workspace));
        MiembroWorkspace member = new MiembroWorkspace();
        member.setUsuario(user);
        member.setWorkspace(workspace);
        member.setRol(request.rol());
        return toResponse(miembroWorkspaceRepository.save(member));
    }

    @Transactional
    public MiembroWorkspaceResponse changeRole(
            Long workspaceId, Long userId, CambiarRolMiembroRequest request) {
        Workspace workspace = requireOwner(workspaceId);
        MiembroWorkspace target = findMember(userId, workspaceId);
        if (target.getRol() == RolWorkspace.OWNER
                && request.rol() != RolWorkspace.OWNER
                && ownerCount(workspace) <= 1) {
            throw new AccesoDenegadoException("El workspace debe conservar al menos un OWNER");
        }
        target.setRol(request.rol());
        return toResponse(miembroWorkspaceRepository.save(target));
    }

    @Transactional
    public void remove(Long workspaceId, Long userId) {
        requireOwner(workspaceId);
        MiembroWorkspace target = findMember(userId, workspaceId);
        if (target.getRol() == RolWorkspace.OWNER) {
            throw new AccesoDenegadoException("No se puede eliminar un OWNER del workspace");
        }
        miembroWorkspaceRepository.delete(target);
    }

    private Workspace requireOwner(Long workspaceId) {
        workspaceAccessService.verificarRol(workspaceId, RolWorkspace.OWNER);
        return workspaceAccessService.obtenerWorkspaceConAcceso(workspaceId);
    }

    private MiembroWorkspace findMember(Long userId, Long workspaceId) {
        return miembroWorkspaceRepository.findByUsuarioIdAndWorkspaceId(userId, workspaceId)
                .orElseThrow(() -> new RecursoNoEncontradoException(
                        "El usuario no pertenece al workspace solicitado"));
    }

    private MiembroTenant createTenantMember(Usuario user, Workspace workspace) {
        MiembroTenant tenantMember = new MiembroTenant();
        tenantMember.setUsuario(user);
        tenantMember.setTenant(workspace.getTenant());
        tenantMember.setRol(RolTenant.MEMBER);
        return miembroTenantRepository.save(tenantMember);
    }

    private long ownerCount(Workspace workspace) {
        return miembroWorkspaceRepository.findByWorkspace(workspace).stream()
                .filter(member -> member.getRol() == RolWorkspace.OWNER)
                .count();
    }

    private MiembroWorkspaceResponse toResponse(MiembroWorkspace member) {
        return new MiembroWorkspaceResponse(
                member.getUsuario().getId(), member.getUsuario().getNombre(),
                member.getUsuario().getEmail(), member.getRol());
    }
}
