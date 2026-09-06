package com.sw1.backend.workspace.service;

import com.sw1.backend.auth.service.UsuarioActualService;
import com.sw1.backend.usuario.model.Usuario;
import com.sw1.backend.workspace.dto.response.WorkspaceResponse;
import com.sw1.backend.workspace.model.MiembroWorkspace;
import com.sw1.backend.workspace.repository.MiembroWorkspaceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Transactional(readOnly = true)
public class WorkspaceService {

    private final UsuarioActualService usuarioActualService;
    private final MiembroWorkspaceRepository miembroWorkspaceRepository;

    public WorkspaceService(
            UsuarioActualService usuarioActualService,
            MiembroWorkspaceRepository miembroWorkspaceRepository) {

        this.usuarioActualService = usuarioActualService;
        this.miembroWorkspaceRepository = miembroWorkspaceRepository;
    }

    public List<WorkspaceResponse> listarMisWorkspaces() {

        Usuario usuario =
                usuarioActualService.obtenerUsuarioActual();

        return miembroWorkspaceRepository
                .findByUsuarioId(usuario.getId())
                .stream()
                .map(this::convertirAResponse)
                .toList();
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