package com.sw1.backend.diagrama.dto.response;
import java.time.LocalDateTime;
import java.util.Map;
public record DiagramaResponse(Long id, Long proyectoId, Integer version, Long revision,
                               Map<String, Object> contenido, LocalDateTime fechaActualizacion) { }
