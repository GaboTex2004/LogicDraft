package com.sw1.backend.proyecto.dto.response;

import java.time.LocalDateTime;

public class ProyectoResponse {

    private Long id;
    private String nombre;
    private String descripcion;
    private LocalDateTime fechaCreacion;
    private Long workspaceId;
    private String workspaceNombre;

    public ProyectoResponse() {
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

    public String getDescripcion() {
        return descripcion;
    }

    public void setDescripcion(String descripcion) {
        this.descripcion = descripcion;
    }

    public LocalDateTime getFechaCreacion() {
        return fechaCreacion;
    }

    public void setFechaCreacion(LocalDateTime fechaCreacion) {
        this.fechaCreacion = fechaCreacion;
    }

    public Long getWorkspaceId() {
        return workspaceId;
    }

    public void setWorkspaceId(Long workspaceId) {
        this.workspaceId = workspaceId;
    }

    public String getWorkspaceNombre() {
        return workspaceNombre;
    }

    public void setWorkspaceNombre(String workspaceNombre) {
        this.workspaceNombre = workspaceNombre;
    }
}
