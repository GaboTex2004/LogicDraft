package com.sw1.backend.tenant.service;

import com.sw1.backend.auth.service.UsuarioActualService;
import com.sw1.backend.common.exception.AccesoDenegadoException;
import com.sw1.backend.tenant.model.MiembroTenant;
import com.sw1.backend.tenant.model.RolTenant;
import com.sw1.backend.tenant.repository.MiembroTenantRepository;
import com.sw1.backend.usuario.model.Usuario;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;

@Service
@Transactional(readOnly = true)
public class TenantAccessService {

    private final UsuarioActualService usuarioActualService;
    private final MiembroTenantRepository miembroTenantRepository;

    public TenantAccessService(
            UsuarioActualService usuarioActualService,
            MiembroTenantRepository miembroTenantRepository) {
        this.usuarioActualService = usuarioActualService;
        this.miembroTenantRepository = miembroTenantRepository;
    }

    public MiembroTenant obtenerMembresia(Long tenantId) {
        Usuario usuario = usuarioActualService.obtenerUsuarioActual();

        return miembroTenantRepository
                .findByUsuarioIdAndTenantId(usuario.getId(), tenantId)
                .orElseThrow(() -> new AccesoDenegadoException(
                        "No tienes acceso al tenant solicitado"));
    }

    public void verificarAcceso(Long tenantId) {
        obtenerMembresia(tenantId);
    }

    public void verificarRol(Long tenantId, RolTenant... rolesPermitidos) {
        MiembroTenant membresia = obtenerMembresia(tenantId);
        boolean rolPermitido = rolesPermitidos != null
                && Arrays.asList(rolesPermitidos).contains(membresia.getRol());

        if (!rolPermitido) {
            throw new AccesoDenegadoException(
                    "No tienes el rol requerido dentro del tenant");
        }
    }
}
