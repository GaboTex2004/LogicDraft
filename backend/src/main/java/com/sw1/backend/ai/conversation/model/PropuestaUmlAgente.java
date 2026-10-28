package com.sw1.backend.ai.conversation.model;

import com.sw1.backend.proyecto.model.Proyecto;
import com.sw1.backend.usuario.model.Usuario;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.Map;

@Entity
@Table(
        name = "propuestas_uml_agente",
        uniqueConstraints = @UniqueConstraint(name = "uk_prop_agente_mensaje", columnNames = "mensaje_id"))
public class PropuestaUmlAgente {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Version
    @Column(name = "version_lock", nullable = false)
    private Long versionLock;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "conversacion_id", nullable = false)
    private ConversacionAgente conversacion;

    @NotNull
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "mensaje_id", nullable = false, unique = true)
    private MensajeConversacionAgente mensaje;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "proyecto_id", nullable = false)
    private Proyecto proyecto;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "solicitado_por_id", nullable = false)
    private Usuario solicitadoPor;

    @NotBlank
    @Size(max = 100)
    @Column(name = "solicitado_por_nombre", nullable = false, length = 100)
    private String solicitadoPorNombre;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AgentProposalState estado = AgentProposalState.PENDING;

    @Column(name = "base_revision")
    private Long baseRevision;

    @Column(name = "aplicada_revision")
    private Long aplicadaRevision;

    @NotNull
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "operaciones_json", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> operacionesJson;

    @NotNull
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "elementos_afectados_json", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> elementosAfectadosJson;

    @Size(max = 500)
    @Column(length = 500)
    private String resumen;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "decidido_por_id")
    private Usuario decididoPor;

    @Size(max = 100)
    @Column(name = "decidido_por_nombre", length = 100)
    private String decididoPorNombre;

    @Column(name = "fecha_decision")
    private LocalDateTime fechaDecision;

    @Size(max = 500)
    @Column(name = "resultado_aplicacion", length = 500)
    private String resultadoAplicacion;

    @Column(name = "fecha_creacion", nullable = false, updatable = false)
    private LocalDateTime fechaCreacion;

    public PropuestaUmlAgente() {
    }

    @PrePersist
    public void asignarFecha() {
        if (fechaCreacion == null) {
            fechaCreacion = LocalDateTime.now();
        }
        if (estado == null) {
            estado = AgentProposalState.PENDING;
        }
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getVersionLock() {
        return versionLock;
    }

    public void setVersionLock(Long versionLock) {
        this.versionLock = versionLock;
    }

    public ConversacionAgente getConversacion() {
        return conversacion;
    }

    public void setConversacion(ConversacionAgente conversacion) {
        this.conversacion = conversacion;
    }

    public MensajeConversacionAgente getMensaje() {
        return mensaje;
    }

    public void setMensaje(MensajeConversacionAgente mensaje) {
        this.mensaje = mensaje;
    }

    public Proyecto getProyecto() {
        return proyecto;
    }

    public void setProyecto(Proyecto proyecto) {
        this.proyecto = proyecto;
    }

    public Usuario getSolicitadoPor() {
        return solicitadoPor;
    }

    public void setSolicitadoPor(Usuario solicitadoPor) {
        this.solicitadoPor = solicitadoPor;
    }

    public String getSolicitadoPorNombre() {
        return solicitadoPorNombre;
    }

    public void setSolicitadoPorNombre(String solicitadoPorNombre) {
        this.solicitadoPorNombre = solicitadoPorNombre;
    }

    public AgentProposalState getEstado() {
        return estado;
    }

    public void setEstado(AgentProposalState estado) {
        this.estado = estado;
    }

    public Long getBaseRevision() {
        return baseRevision;
    }

    public void setBaseRevision(Long baseRevision) {
        this.baseRevision = baseRevision;
    }

    public Long getAplicadaRevision() {
        return aplicadaRevision;
    }

    public void setAplicadaRevision(Long aplicadaRevision) {
        this.aplicadaRevision = aplicadaRevision;
    }

    public Map<String, Object> getOperacionesJson() {
        return operacionesJson;
    }

    public void setOperacionesJson(Map<String, Object> operacionesJson) {
        this.operacionesJson = operacionesJson;
    }

    public Map<String, Object> getElementosAfectadosJson() {
        return elementosAfectadosJson;
    }

    public void setElementosAfectadosJson(Map<String, Object> elementosAfectadosJson) {
        this.elementosAfectadosJson = elementosAfectadosJson;
    }

    public String getResumen() {
        return resumen;
    }

    public void setResumen(String resumen) {
        this.resumen = resumen;
    }

    public Usuario getDecididoPor() {
        return decididoPor;
    }

    public void setDecididoPor(Usuario decididoPor) {
        this.decididoPor = decididoPor;
    }

    public String getDecididoPorNombre() {
        return decididoPorNombre;
    }

    public void setDecididoPorNombre(String decididoPorNombre) {
        this.decididoPorNombre = decididoPorNombre;
    }

    public LocalDateTime getFechaDecision() {
        return fechaDecision;
    }

    public void setFechaDecision(LocalDateTime fechaDecision) {
        this.fechaDecision = fechaDecision;
    }

    public String getResultadoAplicacion() {
        return resultadoAplicacion;
    }

    public void setResultadoAplicacion(String resultadoAplicacion) {
        this.resultadoAplicacion = resultadoAplicacion;
    }

    public LocalDateTime getFechaCreacion() {
        return fechaCreacion;
    }

    public void setFechaCreacion(LocalDateTime fechaCreacion) {
        this.fechaCreacion = fechaCreacion;
    }
}
