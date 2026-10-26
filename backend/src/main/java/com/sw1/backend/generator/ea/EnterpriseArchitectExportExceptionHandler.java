package com.sw1.backend.generator.ea;

import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = EnterpriseArchitectExportController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class EnterpriseArchitectExportExceptionHandler {
    @ExceptionHandler(EnterpriseArchitectXmiException.class)
    ResponseEntity<Map<String, Object>> handle(EnterpriseArchitectXmiException exception, HttpServletRequest request) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("timestamp", LocalDateTime.now()); response.put("status", HttpStatus.UNPROCESSABLE_ENTITY.value());
        response.put("error", HttpStatus.UNPROCESSABLE_ENTITY.getReasonPhrase()); response.put("mensaje", exception.getMessage());
        response.put("ruta", request.getRequestURI());
        return ResponseEntity.unprocessableEntity().body(response);
    }
}
