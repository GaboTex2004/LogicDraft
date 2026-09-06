package com.sw1.backend.ai.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AiGenerateRequest(
        @NotBlank(message = "El prompt es obligatorio")
        @Size(max = 10000, message = "El prompt no debe superar 10000 caracteres")
        String prompt) {
}
