package com.sw1.backend.generator.spring.render;

public final class InfrastructureRenderer {
    private InfrastructureRenderer() {
    }

    public static String application(String basePackage, String applicationType) {
        return """
                package %s;

                import org.springframework.boot.SpringApplication;
                import org.springframework.boot.autoconfigure.SpringBootApplication;

                @SpringBootApplication
                public class %sApplication {
                    public static void main(String[] args) {
                        SpringApplication.run(%sApplication.class, args);
                    }
                }
                """.formatted(basePackage, applicationType, applicationType);
    }

    public static String notFoundException(String basePackage) {
        return """
                package %s.error;

                public class ResourceNotFoundException extends RuntimeException {
                    public ResourceNotFoundException(String message) { super(message); }
                }
                """.formatted(basePackage);
    }

    public static String errorHandler(String basePackage) {
        return """
                package %s.error;

                import java.time.Instant;
                import java.util.LinkedHashMap;
                import java.util.Map;
                import org.springframework.dao.DataIntegrityViolationException;
                import org.springframework.http.*;
                import org.slf4j.*;
                import org.springframework.http.converter.HttpMessageNotReadableException;
                import org.springframework.web.bind.MethodArgumentNotValidException;
                import org.springframework.web.bind.annotation.*;
                import org.springframework.web.server.ResponseStatusException;

                @RestControllerAdvice
                public class GlobalExceptionHandler {
                    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

                    @ExceptionHandler(ResourceNotFoundException.class)
                    public ResponseEntity<Map<String, Object>> notFound(ResourceNotFoundException exception) {
                        return response(HttpStatus.NOT_FOUND, exception.getMessage());
                    }

                    @ExceptionHandler(MethodArgumentNotValidException.class)
                    public ResponseEntity<Map<String, Object>> validation(MethodArgumentNotValidException exception) {
                        return response(HttpStatus.BAD_REQUEST, "La solicitud contiene datos invalidos");
                    }

                    @ExceptionHandler(HttpMessageNotReadableException.class)
                    public ResponseEntity<Map<String, Object>> malformed(HttpMessageNotReadableException exception) {
                        return response(HttpStatus.BAD_REQUEST, "La solicitud contiene JSON invalido");
                    }

                    @ExceptionHandler(ResponseStatusException.class)
                    public ResponseEntity<Map<String, Object>> responseStatus(ResponseStatusException exception) {
                        String message = exception.getReason() == null ? "La operacion no pudo completarse" : exception.getReason();
                        return response(exception.getStatusCode(), message);
                    }

                    @ExceptionHandler(DataIntegrityViolationException.class)
                    public ResponseEntity<Map<String, Object>> conflict(DataIntegrityViolationException exception) {
                        return response(HttpStatus.CONFLICT,
                                "La operacion viola una restriccion de integridad; verifica combinaciones unicas y referencias");
                    }

                    @ExceptionHandler(Exception.class)
                    public ResponseEntity<Map<String, Object>> general(Exception exception) {
                        log.error("Unexpected application error: {}", exception.getClass().getName());
                        return response(HttpStatus.INTERNAL_SERVER_ERROR, "Ocurrio un error interno");
                    }

                    private ResponseEntity<Map<String, Object>> response(HttpStatusCode status, String message) {
                        Map<String, Object> body = new LinkedHashMap<>();
                        body.put("timestamp", Instant.now());
                        body.put("status", status.value());
                        HttpStatus resolved = HttpStatus.resolve(status.value());
                        body.put("error", resolved == null ? "HTTP " + status.value() : resolved.getReasonPhrase());
                        body.put("message", message);
                        return ResponseEntity.status(status).body(body);
                    }
                }
                """.formatted(basePackage);
    }

    public static String cors(String basePackage) {
        return """
                package %s.config;

                import java.util.Arrays;
                import org.springframework.beans.factory.annotation.Value;
                import org.springframework.context.annotation.Configuration;
                import org.springframework.web.servlet.config.annotation.CorsRegistry;
                import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

                @Configuration
                public class CorsConfig implements WebMvcConfigurer {
                    private final String[] origins;

                    public CorsConfig(@Value("${CORS_ALLOWED_ORIGINS:http://localhost:3000}") String origins) {
                        this.origins = Arrays.stream(origins.split(",")).map(String::strip).filter(value -> !value.isEmpty()).toArray(String[]::new);
                    }

                    @Override
                    public void addCorsMappings(CorsRegistry registry) {
                        registry.addMapping("/api/**").allowedOrigins(origins).allowedMethods("GET", "POST", "PUT", "DELETE");
                    }
                }
                """.formatted(basePackage);
    }
}
