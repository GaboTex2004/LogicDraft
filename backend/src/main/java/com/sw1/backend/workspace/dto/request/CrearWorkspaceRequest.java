package com.sw1.backend.workspace.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CrearWorkspaceRequest(

        @NotBlank(message = "El nombre del workspace es obligatorio")
        @Size(max = 120, message = "El nombre no puede superar los 120 caracteres")
        String nombre

) {
}