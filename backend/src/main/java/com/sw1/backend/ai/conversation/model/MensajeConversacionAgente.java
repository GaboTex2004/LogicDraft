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
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

@Entity
@Table(
        name = "mensajes_conversacion_agente",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_msg_agente_conv_client_id",
                columnNames = {"conversacion_id", "client_message_id"}))
public class MensajeConversacionAgente {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "conversacion_id", nullable = false)
    private ConversacionAgente conversacion;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "proyecto_id", nullable = false)
    private Proyecto proyecto;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "autor_tipo", nullable = false, length = 20)
    private MessageAuthorType autorTipo;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "autor_usuario_id")
    private Usuario autorUsuario;

    @NotBlank
    @Size(max = 100)
    @Column(name = "autor_nombre", nullable = false, length = 100)
    private String autorNombre;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "solicitado_por_id")
    private Usuario solicitadoPor;

    @Size(max = 100)
    @Column(name = "solicitado_por_nombre", length = 100)
    private String solicitadoPorNombre;

    @NotBlank
    @Column(nullable = false, columnDefinition = "text")
    private String contenido;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ConversationMessageStatus estado = ConversationMessageStatus.SENT;

    @Size(max = 100)
    @Column(name = "client_message_id", length = 100)
    private String clientMessageId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "mensaje_origen_id")
    private MensajeConversacionAgente mensajeOrigen;

    @Column(name = "fecha_creacion", nullable = false, updatable = false)
    private LocalDateTime fechaCreacion;

    public MensajeConversacionAgente() {
    }

    @PrePersist
    public void asignarFecha() {
        if (fechaCreacion == null) {
            fechaCreacion = LocalDateTime.now();
        }
        if (estado == null) {
            estado = ConversationMessageStatus.SENT;
        }
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public ConversacionAgente getConversacion() {
        return conversacion;
    }

    public void setConversacion(ConversacionAgente conversacion) {
        this.conversacion = conversacion;
    }

    public Proyecto getProyecto() {
        return proyecto;
    }

    public void setProyecto(Proyecto proyecto) {
        this.proyecto = proyecto;
    }

    public MessageAuthorType getAutorTipo() {
        return autorTipo;
    }

    public void setAutorTipo(MessageAuthorType autorTipo) {
        this.autorTipo = autorTipo;
    }

    public Usuario getAutorUsuario() {
        return autorUsuario;
    }

    public void setAutorUsuario(Usuario autorUsuario) {
        this.autorUsuario = autorUsuario;
    }

    public String getAutorNombre() {
        return autorNombre;
    }

    public void setAutorNombre(String autorNombre) {
        this.autorNombre = autorNombre;
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

    public String getContenido() {
        return contenido;
    }

    public void setContenido(String contenido) {
        this.contenido = contenido;
    }

    public ConversationMessageStatus getEstado() {
        return estado;
    }

    public void setEstado(ConversationMessageStatus estado) {
        this.estado = estado;
    }

    public String getClientMessageId() {
        return clientMessageId;
    }

    public void setClientMessageId(String clientMessageId) {
        this.clientMessageId = clientMessageId;
    }

    public MensajeConversacionAgente getMensajeOrigen() {
        return mensajeOrigen;
    }

    public void setMensajeOrigen(MensajeConversacionAgente mensajeOrigen) {
        this.mensajeOrigen = mensajeOrigen;
    }

    public LocalDateTime getFechaCreacion() {
        return fechaCreacion;
    }

    public void setFechaCreacion(LocalDateTime fechaCreacion) {
        this.fechaCreacion = fechaCreacion;
    }
}
