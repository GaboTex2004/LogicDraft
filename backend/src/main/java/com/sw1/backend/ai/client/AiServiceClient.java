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

@Component
public class AiServiceClient {
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
        return com.sw1.backend.ai.diagram.validation.DiagramOperationValidator.validate(
                post("/api/ai/diagram/interpret", request));
    }

    public com.sw1.backend.ai.diagram.dto.DiagramInterpretResponse interpret(
            com.sw1.backend.ai.diagram.dto.ContextualInterpretRequest request) {
        return com.sw1.backend.ai.diagram.validation.DiagramOperationValidator.validate(
                post("/api/ai/diagram/interpret", request));
    }

    public String askAgent(com.sw1.backend.ai.agent.dto.AgentUpstreamRequest request) {
        Map<String, Object> body = post("/api/agent/ask", request);
        if (body == null || !(body.get("answer") instanceof String answer) || answer.isBlank()) {
            throw invalidResponse();
        }
        return answer;
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
}
