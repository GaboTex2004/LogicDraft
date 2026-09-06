package com.sw1.backend.tenant.repository;

import com.sw1.backend.tenant.model.MiembroTenant;
import com.sw1.backend.tenant.model.Tenant;
import com.sw1.backend.usuario.model.Usuario;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MiembroTenantRepository extends JpaRepository<MiembroTenant, Long> {

    Optional<MiembroTenant> findByUsuarioIdAndTenantId(
            Long usuarioId,
            Long tenantId);

    boolean existsByUsuarioAndTenant(Usuario usuario, Tenant tenant);

    List<MiembroTenant> findByTenant(Tenant tenant);

    Optional<MiembroTenant> findByUsuarioAndTenant(Usuario usuario, Tenant tenant);
}
