package com.sw1.backend.auth.service;

import com.sw1.backend.auth.dto.request.LoginRequest;
import com.sw1.backend.auth.dto.request.RegistroRequest;
import com.sw1.backend.auth.dto.response.AuthResponse;
import com.sw1.backend.auth.security.JwtService;
import com.sw1.backend.common.exception.CredencialesInvalidasException;
import com.sw1.backend.common.exception.EmailDuplicadoException;
import com.sw1.backend.tenant.model.MiembroTenant;
import com.sw1.backend.tenant.model.RolTenant;
import com.sw1.backend.tenant.model.Tenant;
import com.sw1.backend.tenant.repository.MiembroTenantRepository;
import com.sw1.backend.tenant.repository.TenantRepository;
import com.sw1.backend.usuario.model.RolSistema;
import com.sw1.backend.usuario.model.Usuario;
import com.sw1.backend.usuario.repository.UsuarioRepository;
import com.sw1.backend.workspace.model.MiembroWorkspace;
import com.sw1.backend.workspace.model.RolWorkspace;
import com.sw1.backend.workspace.model.Workspace;
import com.sw1.backend.workspace.repository.MiembroWorkspaceRepository;
import com.sw1.backend.workspace.repository.WorkspaceRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private final UsuarioRepository usuarioRepository;
    private final TenantRepository tenantRepository;
    private final MiembroTenantRepository miembroTenantRepository;
    private final WorkspaceRepository workspaceRepository;
    private final MiembroWorkspaceRepository miembroWorkspaceRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthService(
            UsuarioRepository usuarioRepository,
            TenantRepository tenantRepository,
            MiembroTenantRepository miembroTenantRepository,
            WorkspaceRepository workspaceRepository,
            MiembroWorkspaceRepository miembroWorkspaceRepository,
            PasswordEncoder passwordEncoder,
            JwtService jwtService) {
        this.usuarioRepository = usuarioRepository;
        this.tenantRepository = tenantRepository;
        this.miembroTenantRepository = miembroTenantRepository;
        this.workspaceRepository = workspaceRepository;
        this.miembroWorkspaceRepository = miembroWorkspaceRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    @Transactional
    public AuthResponse registrar(RegistroRequest request) {
        if (usuarioRepository.existsByEmail(request.getEmail())) {
            throw new EmailDuplicadoException(
                    "Ya existe un usuario registrado con el email indicado");
        }

        Usuario usuario = new Usuario();
        usuario.setNombre(request.getNombre());
        usuario.setEmail(request.getEmail());
        usuario.setPassword(passwordEncoder.encode(request.getPassword()));
        usuario.setRolSistema(RolSistema.USER);
        Usuario usuarioGuardado = usuarioRepository.save(usuario);

        Tenant tenant = new Tenant();
        tenant.setNombre("Organización de " + usuarioGuardado.getNombre());
        Tenant tenantGuardado = tenantRepository.save(tenant);

        MiembroTenant miembroTenant = new MiembroTenant();
        miembroTenant.setUsuario(usuarioGuardado);
        miembroTenant.setTenant(tenantGuardado);
        miembroTenant.setRol(RolTenant.OWNER);
        miembroTenantRepository.save(miembroTenant);

        Workspace workspace = new Workspace();
        workspace.setNombre("Workspace de " + usuarioGuardado.getNombre());
        workspace.setTenant(tenantGuardado);
        Workspace workspaceGuardado = workspaceRepository.save(workspace);

        MiembroWorkspace miembro = new MiembroWorkspace();
        miembro.setUsuario(usuarioGuardado);
        miembro.setWorkspace(workspaceGuardado);
        miembro.setRol(RolWorkspace.OWNER);
        miembroWorkspaceRepository.save(miembro);

        return crearRespuesta(usuarioGuardado);
    }

    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest request) {
        Usuario usuario = usuarioRepository.findByEmail(request.getEmail())
                .orElseThrow(this::credencialesInvalidas);

        if (!passwordEncoder.matches(request.getPassword(), usuario.getPassword())) {
            throw credencialesInvalidas();
        }

        return crearRespuesta(usuario);
    }

    private AuthResponse crearRespuesta(Usuario usuario) {
        AuthResponse response = new AuthResponse();
        response.setToken(jwtService.generarToken(
                usuario.getEmail(),
                usuario.getRolSistema()));
        response.setUsuarioId(usuario.getId());
        response.setNombre(usuario.getNombre());
        response.setEmail(usuario.getEmail());
        response.setRolSistema(usuario.getRolSistema());
        return response;
    }

    private CredencialesInvalidasException credencialesInvalidas() {
        return new CredencialesInvalidasException("Email o contraseña incorrectos");
    }
}
