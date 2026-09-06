package com.sw1.backend.workspace.member.dto;

import com.sw1.backend.workspace.model.RolWorkspace;

public record MiembroWorkspaceResponse(
        Long userId,
        String nombre,
        String email,
        RolWorkspace rol) {
}
