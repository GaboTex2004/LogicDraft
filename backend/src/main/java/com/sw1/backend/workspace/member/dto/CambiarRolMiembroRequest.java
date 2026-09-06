package com.sw1.backend.workspace.member.dto;

import com.sw1.backend.workspace.model.RolWorkspace;
import jakarta.validation.constraints.NotNull;

public record CambiarRolMiembroRequest(
        @NotNull(message = "El rol es obligatorio") RolWorkspace rol) {
}
