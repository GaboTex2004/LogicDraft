package com.sw1.backend.common.exception;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.HttpRequestMethodNotSupportedException;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(com.sw1.backend.ai.client.AiServiceException.class)
    public ResponseEntity<Map<String, Object>> manejarErrorIA(
            com.sw1.backend.ai.client.AiServiceException exception,
            HttpServletRequest request) {
        return ResponseEntity.status(exception.getStatus()).body(crearRespuestaBase(
                exception.getStatus(), exception.getMessage(), request.getRequestURI()));
    }

    @ExceptionHandler(com.sw1.backend.diagrama.validation.DiagramValidationException.class)
    public ResponseEntity<Map<String, Object>> manejarDiagramaInvalido(
            com.sw1.backend.diagrama.validation.DiagramValidationException exception,
            HttpServletRequest request) {
        return ResponseEntity.unprocessableEntity().body(crearRespuestaBase(
                HttpStatus.UNPROCESSABLE_ENTITY, exception.getMessage(), request.getRequestURI()));
    }

    @ExceptionHandler(com.sw1.backend.collaboration.service.ElementLockedException.class)
    public ResponseEntity<Map<String, Object>> manejarElementoBloqueado(
            com.sw1.backend.collaboration.service.ElementLockedException exception,
            HttpServletRequest request) {
        Map<String, Object> body = crearRespuestaBase(
                HttpStatus.CONFLICT, exception.getMessage(), request.getRequestURI());
        body.put("code", "ELEMENT_LOCKED");
        body.put("elementId", exception.getElementId());
        body.put("lockedByUserId", exception.getOwnerUserId());
        body.put("lockedByName", exception.getOwnerName());
        if (exception.getOwnerClientId() != null) {
            body.put("lockedByClientId", exception.getOwnerClientId());
        }
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    @ExceptionHandler(com.sw1.backend.ai.conversation.exception.ConversationConflictException.class)
    public ResponseEntity<Map<String, Object>> manejarConflictoConversacion(
            com.sw1.backend.ai.conversation.exception.ConversationConflictException exception,
            HttpServletRequest request) {
        Map<String, Object> body = crearRespuestaBase(
                HttpStatus.CONFLICT, exception.getMessage(), request.getRequestURI());
        if (exception.getCode() != null) {
            body.put("code", exception.getCode());
        }
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    @ExceptionHandler(com.sw1.backend.ai.conversation.exception.ConversationValidationException.class)
    public ResponseEntity<Map<String, Object>> manejarValidacionConversacion(
            com.sw1.backend.ai.conversation.exception.ConversationValidationException exception,
            HttpServletRequest request) {
        return ResponseEntity.badRequest().body(crearRespuestaBase(
                HttpStatus.BAD_REQUEST, exception.getMessage(), request.getRequestURI()));
    }

    @ExceptionHandler(com.sw1.backend.diagrama.service.DiagramConflictException.class)
    public ResponseEntity<Map<String, Object>> manejarConflictoDeDiagrama(
            com.sw1.backend.diagrama.service.DiagramConflictException exception,
            HttpServletRequest request) {
        Map<String, Object> body = crearRespuestaBase(
                HttpStatus.CONFLICT, exception.getMessage(), request.getRequestURI());
        if (exception.getCurrentRevision() != null)
            body.put("revisionActual", exception.getCurrentRevision());
        body.put("recargar", "/api/proyectos/" + exception.getProjectId() + "/diagrama");
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    private static final Logger log =
        LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(AccesoDenegadoException.class)
    public ResponseEntity<Map<String, Object>> manejarAccesoDenegado(
            AccesoDenegadoException exception,
            HttpServletRequest request) {
        Map<String, Object> respuesta = crearRespuestaBase(
                HttpStatus.FORBIDDEN,
                exception.getMessage(),
                request.getRequestURI()
        );

        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(respuesta);
    }

    @ExceptionHandler(EmailDuplicadoException.class)
    public ResponseEntity<Map<String, Object>> manejarEmailDuplicado(
            EmailDuplicadoException exception,
            HttpServletRequest request) {
        Map<String, Object> respuesta = crearRespuestaBase(
                HttpStatus.CONFLICT,
                exception.getMessage(),
                request.getRequestURI()
        );

        return ResponseEntity.status(HttpStatus.CONFLICT).body(respuesta);
    }

    @ExceptionHandler(CredencialesInvalidasException.class)
    public ResponseEntity<Map<String, Object>> manejarCredencialesInvalidas(
            CredencialesInvalidasException exception,
            HttpServletRequest request) {
        Map<String, Object> respuesta = crearRespuestaBase(
                HttpStatus.UNAUTHORIZED,
                exception.getMessage(),
                request.getRequestURI()
        );

        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(respuesta);
    }

    @ExceptionHandler(RecursoNoEncontradoException.class)
    public ResponseEntity<Map<String, Object>> manejarRecursoNoEncontrado(
            RecursoNoEncontradoException exception,
            HttpServletRequest request) {
        Map<String, Object> respuesta = crearRespuestaBase(
                HttpStatus.NOT_FOUND,
                exception.getMessage(),
                request.getRequestURI()
        );

        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(respuesta);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> manejarJsonInvalido(
            HttpMessageNotReadableException ex,
            HttpServletRequest request) {

        Map<String, Object> body = new HashMap<>();

        body.put("timestamp", LocalDateTime.now());
        body.put("status", 400);
        body.put("error", "Bad Request");
        body.put("mensaje", "El cuerpo JSON no tiene un formato válido");
        body.put("ruta", request.getRequestURI());

        return ResponseEntity.badRequest().body(body);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> manejarErroresDeValidacion(
            MethodArgumentNotValidException exception,
            HttpServletRequest request) {
        Map<String, String> errores = new LinkedHashMap<>();
        for (FieldError error : exception.getBindingResult().getFieldErrors()) {
            errores.putIfAbsent(error.getField(), error.getDefaultMessage());
        }

        Map<String, Object> respuesta = crearRespuestaBase(
                HttpStatus.BAD_REQUEST,
                "La solicitud contiene datos inválidos",
                request.getRequestURI()
        );
        respuesta.put("errores", errores);

        return ResponseEntity.badRequest().body(respuesta);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> manejarMetodoNoPermitido(
            HttpRequestMethodNotSupportedException exception,
            HttpServletRequest request) {

        Map<String, Object> respuesta = crearRespuestaBase(
                HttpStatus.METHOD_NOT_ALLOWED,
                "Método HTTP no permitido",
                request.getRequestURI()
        );

        return ResponseEntity
                .status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(respuesta);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> manejarErrorGeneral(
            Exception exception,
            HttpServletRequest request) {

        log.error(
                "Error no controlado en {}",
                request.getRequestURI(),
                exception
        );

        Map<String, Object> respuesta = crearRespuestaBase(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Ocurrió un error interno en el servidor",
                request.getRequestURI()
        );

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(respuesta);
    }
    private Map<String, Object> crearRespuestaBase(
            HttpStatus estado,
            String mensaje,
            String ruta) {
        Map<String, Object> respuesta = new LinkedHashMap<>();
        respuesta.put("timestamp", LocalDateTime.now());
        respuesta.put("status", estado.value());
        respuesta.put("error", estado.getReasonPhrase());
        respuesta.put("mensaje", mensaje);
        respuesta.put("ruta", ruta);
        return respuesta;
    }

}
