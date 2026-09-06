package com.sw1.backend.workspace.member.dto;

import com.sw1.backend.workspace.model.RolWorkspace;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record AgregarMiembroWorkspaceRequest(
        @NotBlank(message = "El email es obligatorio")
        @Email(message = "El email no tiene un formato válido")
        String email,
        @NotNull(message = "El rol es obligatorio")
        RolWorkspace rol) {
}
