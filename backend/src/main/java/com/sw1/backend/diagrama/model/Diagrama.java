package com.sw1.backend.diagrama.model;

import com.sw1.backend.proyecto.model.Proyecto;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.LocalDateTime;
import java.util.Map;

@Entity
@Table(name = "diagramas", uniqueConstraints = @UniqueConstraint(name = "uk_diagrama_proyecto", columnNames = "proyecto_id"))
public class Diagrama {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @NotNull @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "proyecto_id", nullable = false)
    private Proyecto proyecto;
    @NotNull @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> contenido;
    @NotNull @Column(nullable = false)
    private Integer version;
    @Column(nullable = false, updatable = false)
    private LocalDateTime fechaCreacion;
    @Column(nullable = false)
    private LocalDateTime fechaActualizacion;
    @PrePersist public void asignarFechas() { LocalDateTime ahora = LocalDateTime.now(); if (fechaCreacion == null) fechaCreacion = ahora; fechaActualizacion = ahora; }
    @PreUpdate public void actualizarFecha() { fechaActualizacion = LocalDateTime.now(); }
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Proyecto getProyecto() { return proyecto; }
    public void setProyecto(Proyecto proyecto) { this.proyecto = proyecto; }
    public Map<String, Object> getContenido() { return contenido; }
    public void setContenido(Map<String, Object> contenido) { this.contenido = contenido; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
    public LocalDateTime getFechaCreacion() { return fechaCreacion; }
    public void setFechaCreacion(LocalDateTime fechaCreacion) { this.fechaCreacion = fechaCreacion; }
    public LocalDateTime getFechaActualizacion() { return fechaActualizacion; }
    public void setFechaActualizacion(LocalDateTime fechaActualizacion) { this.fechaActualizacion = fechaActualizacion; }
}
