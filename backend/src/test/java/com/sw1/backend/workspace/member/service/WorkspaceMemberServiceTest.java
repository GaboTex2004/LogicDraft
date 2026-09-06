package com.sw1.backend.workspace.member.service;

import com.sw1.backend.common.exception.AccesoDenegadoException;
import com.sw1.backend.common.exception.EmailDuplicadoException;
import com.sw1.backend.tenant.model.MiembroTenant;
import com.sw1.backend.tenant.model.Tenant;
import com.sw1.backend.tenant.repository.MiembroTenantRepository;
import com.sw1.backend.usuario.model.Usuario;
import com.sw1.backend.usuario.repository.UsuarioRepository;
import com.sw1.backend.workspace.member.dto.AgregarMiembroWorkspaceRequest;
import com.sw1.backend.workspace.member.dto.CambiarRolMiembroRequest;
import com.sw1.backend.workspace.model.MiembroWorkspace;
import com.sw1.backend.workspace.model.RolWorkspace;
import com.sw1.backend.workspace.model.Workspace;
import com.sw1.backend.workspace.repository.MiembroWorkspaceRepository;
import com.sw1.backend.workspace.service.WorkspaceAccessService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkspaceMemberServiceTest {
    @Mock private WorkspaceAccessService accessService;
    @Mock private UsuarioRepository usuarioRepository;
    @Mock private MiembroWorkspaceRepository workspaceMemberRepository;
    @Mock private MiembroTenantRepository tenantMemberRepository;
    private WorkspaceMemberService service;
    private Workspace workspace;
    private Usuario collaborator;

    @BeforeEach
    void setUp() {
        service = new WorkspaceMemberService(accessService,
                usuarioRepository, workspaceMemberRepository, tenantMemberRepository);
        Tenant tenant = new Tenant();
        tenant.setId(30L);
        workspace = new Workspace();
        workspace.setId(20L);
        workspace.setTenant(tenant);
        collaborator = new Usuario();
        collaborator.setId(7L);
        collaborator.setNombre("Carlos");
        collaborator.setEmail("carlos@example.com");
    }

    @Test
    void ownerAgregaEditorYCompletaMembresiaTenant() {
        when(accessService.obtenerWorkspaceConAcceso(20L)).thenReturn(workspace);
        when(usuarioRepository.findByEmail("carlos@example.com")).thenReturn(Optional.of(collaborator));
        when(workspaceMemberRepository.existsByUsuarioAndWorkspace(collaborator, workspace)).thenReturn(false);
        when(tenantMemberRepository.findByUsuarioAndTenant(collaborator, workspace.getTenant())).thenReturn(Optional.empty());
        when(tenantMemberRepository.save(any(MiembroTenant.class))).thenAnswer(call -> call.getArgument(0));
        when(workspaceMemberRepository.save(any(MiembroWorkspace.class))).thenAnswer(call -> call.getArgument(0));

        var response = service.add(20L,
                new AgregarMiembroWorkspaceRequest("carlos@example.com", RolWorkspace.EDITOR));

        assertEquals(RolWorkspace.EDITOR, response.rol());
        verify(accessService).verificarRol(20L, RolWorkspace.OWNER);
        verify(tenantMemberRepository).save(any(MiembroTenant.class));
    }

    @Test
    void noDuplicaMembresiaExistente() {
        when(accessService.obtenerWorkspaceConAcceso(20L)).thenReturn(workspace);
        when(usuarioRepository.findByEmail("carlos@example.com")).thenReturn(Optional.of(collaborator));
        when(workspaceMemberRepository.existsByUsuarioAndWorkspace(collaborator, workspace)).thenReturn(true);

        assertThrows(EmailDuplicadoException.class, () -> service.add(20L,
                new AgregarMiembroWorkspaceRequest("carlos@example.com", RolWorkspace.EDITOR)));
        verify(workspaceMemberRepository, never()).save(any(MiembroWorkspace.class));
    }

    @Test
    void nuncaEliminaUnOwner() {
        when(accessService.obtenerWorkspaceConAcceso(20L)).thenReturn(workspace);
        MiembroWorkspace owner = member(RolWorkspace.OWNER);
        when(workspaceMemberRepository.findByUsuarioIdAndWorkspaceId(7L, 20L)).thenReturn(Optional.of(owner));

        assertThrows(AccesoDenegadoException.class, () -> service.remove(20L, 7L));
        verify(workspaceMemberRepository, never()).delete(any(MiembroWorkspace.class));
    }

    @Test
    void noDegradaAlUnicoOwner() {
        when(accessService.obtenerWorkspaceConAcceso(20L)).thenReturn(workspace);
        MiembroWorkspace owner = member(RolWorkspace.OWNER);
        when(workspaceMemberRepository.findByUsuarioIdAndWorkspaceId(7L, 20L)).thenReturn(Optional.of(owner));
        when(workspaceMemberRepository.findByWorkspace(workspace)).thenReturn(List.of(owner));

        assertThrows(AccesoDenegadoException.class, () -> service.changeRole(
                20L, 7L, new CambiarRolMiembroRequest(RolWorkspace.EDITOR)));
    }

    private MiembroWorkspace member(RolWorkspace role) {
        MiembroWorkspace member = new MiembroWorkspace();
        member.setUsuario(collaborator);
        member.setWorkspace(workspace);
        member.setRol(role);
        return member;
    }
}
