package com.sw1.backend.auth.dto.response;

public class UsuarioActualResponse {

    private Long id;
    private String nombre;
    private String email;
    private String rolSistema;

    public UsuarioActualResponse() {
    }

    public UsuarioActualResponse(
            Long id,
            String nombre,
            String email,
            String rolSistema) {
        this.id = id;
        this.nombre = nombre;
        this.email = email;
        this.rolSistema = rolSistema;
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

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getRolSistema() {
        return rolSistema;
    }

    public void setRolSistema(String rolSistema) {
        this.rolSistema = rolSistema;
    }
}