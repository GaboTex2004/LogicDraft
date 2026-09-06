package com.sw1.backend.workspace.dto.response;

public class WorkspaceResponse {

    private Long id;
    private String nombre;
    private Long tenantId;
    private String rol;

    public WorkspaceResponse() {
    }

    public WorkspaceResponse(
            Long id,
            String nombre,
            Long tenantId,
            String rol) {

        this.id = id;
        this.nombre = nombre;
        this.tenantId = tenantId;
        this.rol = rol;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getNombre() {
        return nombre;
    }

    public void setNombre(String nombre) {
        this.nombre = nombre;
    }

    public Long getTenantId() {
        return tenantId;
    }

    public void setTenantId(Long tenantId) {
        this.tenantId = tenantId;
    }

    public String getRol() {
        return rol;
    }

    public void setRol(String rol) {
        this.rol = rol;
    }
}