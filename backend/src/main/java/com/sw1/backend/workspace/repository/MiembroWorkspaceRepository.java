package com.sw1.backend.workspace.repository;

import com.sw1.backend.usuario.model.Usuario;
import com.sw1.backend.workspace.model.MiembroWorkspace;
import com.sw1.backend.workspace.model.Workspace;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MiembroWorkspaceRepository extends JpaRepository<MiembroWorkspace, Long> {
    List<MiembroWorkspace> findByUsuarioId(Long usuarioId);
    Optional<MiembroWorkspace> findByUsuarioIdAndWorkspaceId(
            Long usuarioId,
            Long workspaceId);

    List<MiembroWorkspace> findByWorkspace(Workspace workspace);

    boolean existsByUsuarioAndWorkspace(Usuario usuario, Workspace workspace);
}
