package com.sw1.backend.collaboration.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

public class ElementLockRequest {
    @NotBlank(message = "El clientId es obligatorio")
    @Pattern(regexp = "^[A-Za-z0-9_-]{1,100}$", message = "El clientId tiene un formato inválido")
    private String clientId;

    @Size(max = 200, message = "No se pueden solicitar más de 200 elementos a la vez")
    private List<String> elementIds;

    public ElementLockRequest() {}

    public ElementLockRequest(String clientId, List<String> elementIds) {
        this.clientId = clientId;
        this.elementIds = elementIds;
    }

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    public List<String> getElementIds() {
        return elementIds;
    }

    public void setElementIds(List<String> elementIds) {
        this.elementIds = elementIds;
    }
}
