package com.sw1.backend.collaboration.security;

import com.sw1.backend.common.exception.AccesoDenegadoException;
import com.sw1.backend.proyecto.model.Proyecto;
import com.sw1.backend.proyecto.repository.ProyectoRepository;
import com.sw1.backend.tenant.model.MiembroTenant;
import com.sw1.backend.tenant.model.Tenant;
import com.sw1.backend.tenant.repository.MiembroTenantRepository;
import com.sw1.backend.usuario.model.Usuario;
import com.sw1.backend.usuario.repository.UsuarioRepository;
import com.sw1.backend.workspace.model.MiembroWorkspace;
import com.sw1.backend.workspace.model.Workspace;
import com.sw1.backend.workspace.model.RolWorkspace;
import com.sw1.backend.workspace.repository.MiembroWorkspaceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.security.Principal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProjectCollaborationAuthorizerTest {
    @Mock private UsuarioRepository usuarioRepository;
    @Mock private ProyectoRepository proyectoRepository;
    @Mock private MiembroTenantRepository miembroTenantRepository;
    @Mock private MiembroWorkspaceRepository miembroWorkspaceRepository;
    private ProjectCollaborationAuthorizer authorizer;
    private final Principal principal = () -> "ana@example.com";

    @BeforeEach
    void setUp() {
        authorizer = new ProjectCollaborationAuthorizer(
                usuarioRepository, proyectoRepository,
                miembroTenantRepository, miembroWorkspaceRepository);
    }

    @Test
    void autorizaSoloConMembresiaDeTenantYWorkspace() {
        Usuario usuario = user();
        prepareProjectAndUser(usuario);
        when(miembroTenantRepository.findByUsuarioIdAndTenantId(7L, 30L))
                .thenReturn(Optional.of(new MiembroTenant()));
        when(miembroWorkspaceRepository.findByUsuarioIdAndWorkspaceId(7L, 20L))
                .thenReturn(Optional.of(new MiembroWorkspace()));

        CollaborationIdentity identity = authorizer.authorize(10L, principal);

        assertEquals(7L, identity.userId());
        assertEquals("Ana", identity.name());
    }

    @Test
    void rechazaUsuarioDelTenantQueNoPerteneceAlWorkspace() {
        Usuario usuario = user();
        prepareProjectAndUser(usuario);
        when(miembroTenantRepository.findByUsuarioIdAndTenantId(7L, 30L))
                .thenReturn(Optional.of(new MiembroTenant()));
        when(miembroWorkspaceRepository.findByUsuarioIdAndWorkspaceId(7L, 20L))
                .thenReturn(Optional.empty());

        assertThrows(AccesoDenegadoException.class, () -> authorizer.authorize(10L, principal));
    }

    @Test
    void autorizaPublicacionRealtimeParaEditor() {
        Usuario usuario = user();
        prepareProjectAndUser(usuario);
        MiembroWorkspace membership = new MiembroWorkspace();
        membership.setRol(RolWorkspace.EDITOR);
        when(miembroTenantRepository.findByUsuarioIdAndTenantId(7L, 30L))
                .thenReturn(Optional.of(new MiembroTenant()));
        when(miembroWorkspaceRepository.findByUsuarioIdAndWorkspaceId(7L, 20L))
                .thenReturn(Optional.of(membership));

        CollaborationIdentity identity = authorizer.authorizeEditor(10L, principal);

        assertEquals(7L, identity.userId());
    }

    @Test
    void rechazaPublicacionRealtimeParaViewer() {
        Usuario usuario = user();
        prepareProjectAndUser(usuario);
        MiembroWorkspace membership = new MiembroWorkspace();
        membership.setRol(RolWorkspace.VIEWER);
        when(miembroTenantRepository.findByUsuarioIdAndTenantId(7L, 30L))
                .thenReturn(Optional.of(new MiembroTenant()));
        when(miembroWorkspaceRepository.findByUsuarioIdAndWorkspaceId(7L, 20L))
                .thenReturn(Optional.of(membership));

        assertThrows(AccesoDenegadoException.class,
                () -> authorizer.authorizeEditor(10L, principal));
    }

    private void prepareProjectAndUser(Usuario usuario) {
        Tenant tenant = new Tenant();
        tenant.setId(30L);
        Workspace workspace = new Workspace();
        workspace.setId(20L);
        workspace.setTenant(tenant);
        Proyecto proyecto = new Proyecto();
        proyecto.setId(10L);
        proyecto.setWorkspace(workspace);
        when(usuarioRepository.findByEmail("ana@example.com")).thenReturn(Optional.of(usuario));
        when(proyectoRepository.findById(10L)).thenReturn(Optional.of(proyecto));
    }

    private Usuario user() {
        Usuario usuario = new Usuario();
        usuario.setId(7L);
        usuario.setNombre("Ana");
        usuario.setEmail("ana@example.com");
        return usuario;
    }
}
