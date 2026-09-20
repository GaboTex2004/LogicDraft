package com.sw1.backend.ai.client;

import com.sw1.backend.ai.dto.request.AiGenerateRequest;
import com.sw1.backend.ai.dto.response.AiGenerateResponse;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component
public class AiServiceClient {
    private static final Logger log = LoggerFactory.getLogger(AiServiceClient.class);
    private final RestClient client;

    public AiServiceClient(
            @Value("${ai.service.url}") String baseUrl,
            @Value("${ai.service.timeout-seconds}") long timeoutSeconds) {
        if (timeoutSeconds <= 0) throw new IllegalArgumentException("AI service timeout must be positive");
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(Math.min(10, timeoutSeconds)));
        factory.setReadTimeout(Duration.ofSeconds(timeoutSeconds));
        client = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }

    public AiGenerateResponse generate(AiGenerateRequest request) {
        Map<String, Object> body = post("/api/ai/generate", request);
        if (body == null || !(body.get("content") instanceof String content) || content.isBlank()) {
            throw invalidResponse();
        }
        return new AiGenerateResponse(content);
    }

    public com.sw1.backend.ai.diagram.dto.DiagramInterpretResponse interpret(AiGenerateRequest request) {
        return validateDiagramResponse(post("/api/ai/diagram/interpret", request));
    }

    public com.sw1.backend.ai.diagram.dto.DiagramInterpretResponse interpret(
            com.sw1.backend.ai.diagram.dto.ContextualInterpretRequest request) {
        return validateDiagramResponse(post("/api/ai/diagram/interpret", request));
    }

    private static com.sw1.backend.ai.diagram.dto.DiagramInterpretResponse validateDiagramResponse(
            Map<String, Object> raw) {
        var response = com.sw1.backend.ai.diagram.validation.DiagramOperationValidator.validate(raw);
        log.info("Diagram AI batch stage=spring_received count={} types={}", response.operations().size(),
                response.operations().stream().map(operation -> operation.type().name()).toList());
        return response;
    }

    public com.sw1.backend.ai.agent.dto.AgentAskResponse askAgent(com.sw1.backend.ai.agent.dto.AgentUpstreamRequest request) {
        Map<String, Object> body = post("/api/agent/ask", request);
        if (body == null || !(body.get("answer") instanceof String answer) || answer.isBlank()) {
            throw invalidResponse();
        }
        Object operations = body.getOrDefault("operations", java.util.List.of());
        var validated = com.sw1.backend.ai.diagram.validation.DiagramOperationValidator.validate(
                Map.of("operations", operations));
        return new com.sw1.backend.ai.agent.dto.AgentAskResponse(answer, validated.operations());
    }

    private Map<String, Object> post(String path, Object request) {
        try {
            Map<String, Object> body = client.post().uri(path)
                    .contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON)
                    .body(request).retrieve()
                    .onStatus(status -> status.isError(), (req, res) -> {
                        int code = res.getStatusCode().value();
                        if (code == 504) throw timeout();
                        if (code == 503) throw unavailable();
                        if (code == 422) throw incompleteResponse();
                        if (code >= 500 && code != 502) throw upstreamInternalError();
                        throw invalidResponse();
                    })
                    .body(new ParameterizedTypeReference<Map<String, Object>>() {});
            return body;
        } catch (ResourceAccessException exception) {
            for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
                if (cause instanceof SocketTimeoutException) throw timeout();
            }
            throw unavailable();
        } catch (RestClientException exception) {
            for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
                if (cause instanceof SocketTimeoutException) throw timeout();
            }
            throw invalidResponse();
        }
    }

    private static AiServiceException timeout() {
        return new AiServiceException(HttpStatus.GATEWAY_TIMEOUT, "El servicio de IA excedio el tiempo de espera");
    }

    private static AiServiceException unavailable() {
        return new AiServiceException(HttpStatus.SERVICE_UNAVAILABLE, "El servicio de IA no esta disponible");
    }

    private static AiServiceException invalidResponse() {
        return new AiServiceException(HttpStatus.BAD_GATEWAY, "El servicio de IA no devolvio una respuesta valida");
    }

    private static AiServiceException incompleteResponse() {
        return new AiServiceException(HttpStatus.UNPROCESSABLE_ENTITY,
                "La IA no pudo completar todas las modificaciones solicitadas");
    }

    private static AiServiceException upstreamInternalError() {
        return new AiServiceException(HttpStatus.BAD_GATEWAY,
                "El servicio de IA tuvo un error interno");
    }
}
