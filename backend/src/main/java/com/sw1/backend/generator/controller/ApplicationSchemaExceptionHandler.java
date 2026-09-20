package com.sw1.backend.generator.controller;

import com.sw1.backend.generator.validation.ApplicationSchemaException;
import com.sw1.backend.generator.spring.SpringGeneratorException;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = {ApplicationSchemaController.class, SpringGenerationController.class,
        FullStackGenerationController.class})
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ApplicationSchemaExceptionHandler {
    @ExceptionHandler(ApplicationSchemaException.class)
    public ResponseEntity<Map<String, Object>> handle(ApplicationSchemaException exception,
                                                       HttpServletRequest request) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("timestamp", LocalDateTime.now());
        response.put("status", HttpStatus.CONFLICT.value());
        response.put("error", HttpStatus.CONFLICT.getReasonPhrase());
        response.put("mensaje", exception.getMessage());
        response.put("ruta", request.getRequestURI());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(response);
    }

    @ExceptionHandler(SpringGeneratorException.class)
    public ResponseEntity<Map<String, Object>> handle(SpringGeneratorException exception,
                                                       HttpServletRequest request) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("timestamp", LocalDateTime.now());
        response.put("status", HttpStatus.CONFLICT.value());
        response.put("error", HttpStatus.CONFLICT.getReasonPhrase());
        response.put("mensaje", exception.getMessage());
        response.put("ruta", request.getRequestURI());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(response);
    }
}
