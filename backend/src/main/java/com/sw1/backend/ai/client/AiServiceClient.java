package com.sw1.backend.ai.client;

import com.sw1.backend.ai.client.AiServiceException.ErrorCategory;
import com.sw1.backend.ai.dto.request.AiGenerateRequest;
import com.sw1.backend.ai.dto.response.AiGenerateResponse;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
            throw invalidResponse("Campo content ausente o vacio en /api/ai/generate");
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

    public com.sw1.backend.ai.agent.dto.AgentAskResponse askAgent(
            com.sw1.backend.ai.agent.dto.AgentUpstreamRequest request) {
        var ctx = request != null ? request.context() : null;
        log.info("AI agent call stage=spring_to_fastapi endpoint=/api/agent/ask projectId={} diagramId={} entities={} relationships={} associations={} historyCount={}",
                ctx != null ? ctx.projectId() : null,
                ctx != null ? ctx.diagramId() : null,
                ctx != null && ctx.entities() != null ? ctx.entities().size() : 0,
                ctx != null && ctx.relationships() != null ? ctx.relationships().size() : 0,
                ctx != null && ctx.associations() != null ? ctx.associations().size() : 0,
                request != null && request.conversation() != null ? request.conversation().size() : 0);
        Map<String, Object> body = post("/api/agent/ask", request);
        if (body == null || !(body.get("answer") instanceof String answer) || answer.isBlank()) {
            throw invalidResponse("Campo answer ausente o vacio en /api/agent/ask");
        }
        Object operations = body.getOrDefault("operations", java.util.List.of());
        var validated = com.sw1.backend.ai.diagram.validation.DiagramOperationValidator.validate(
                Map.of("operations", operations));
        log.info("AI agent call stage=spring_received_agent_reply projectId={} operationsCount={}",
                ctx != null ? ctx.projectId() : null, validated.operations().size());
        return new com.sw1.backend.ai.agent.dto.AgentAskResponse(answer, validated.operations());
    }

    private Map<String, Object> post(String path, Object request) {
        long startedNs = System.nanoTime();
        try {
            Map<String, Object> body = client.post().uri(path)
                    .contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON)
                    .body(request).retrieve()
                    .onStatus(status -> status.isError(), (req, res) -> {
                        int code = res.getStatusCode().value();
                        if (code == 504) throw providerTimeout(code);
                        if (code == 503) throw providerUnavailable(code);
                        if (code == 422) throw incompleteResponse(code);
                        if (code >= 500 && code != 502) throw upstreamInternalError(code);
                        throw invalidResponse("FastAPI respondio HTTP " + code);
                    })
                    .body(new ParameterizedTypeReference<Map<String, Object>>() {});
            long elapsedMs = Duration.ofNanos(System.nanoTime() - startedNs).toMillis();
            log.info("AI upstream call stage=fastapi_responded endpoint={} elapsedMs={}", path, elapsedMs);
            return body;
        } catch (AiServiceException aiError) {
            long elapsedMs = Duration.ofNanos(System.nanoTime() - startedNs).toMillis();
            log.warn("AI upstream call stage=fastapi_failed endpoint={} category={} status={} elapsedMs={} detail={}",
                    path, aiError.getCategory(), aiError.getStatus().value(), elapsedMs, aiError.getTechnicalDetail());
            throw aiError;
        } catch (ResourceAccessException exception) {
            AiServiceException mapped = hasSocketTimeout(exception)
                    ? fastApiCommunicationTimeout()
                    : fastApiUnreachable(exception);
            long elapsedMs = Duration.ofNanos(System.nanoTime() - startedNs).toMillis();
            log.warn("AI upstream call stage=fastapi_failed endpoint={} category={} status={} elapsedMs={} detail={}",
                    path, mapped.getCategory(), mapped.getStatus().value(), elapsedMs, mapped.getTechnicalDetail());
            throw mapped;
        } catch (RestClientException exception) {
            AiServiceException mapped = hasSocketTimeout(exception)
                    ? fastApiCommunicationTimeout()
                    : invalidResponse("Respuesta HTTP no procesable como JSON desde FastAPI");
            long elapsedMs = Duration.ofNanos(System.nanoTime() - startedNs).toMillis();
            log.warn("AI upstream call stage=fastapi_failed endpoint={} category={} status={} elapsedMs={} detail={}",
                    path, mapped.getCategory(), mapped.getStatus().value(), elapsedMs, mapped.getTechnicalDetail());
            throw mapped;
        }
    }

    private static boolean hasSocketTimeout(Throwable throwable) {
        for (Throwable cause = throwable; cause != null; cause = cause.getCause()) {
            if (cause instanceof SocketTimeoutException) {
                return true;
            }
        }
        return false;
    }

    private static AiServiceException providerTimeout(int code) {
        return new AiServiceException(
                HttpStatus.GATEWAY_TIMEOUT,
                ErrorCategory.AI_PROVIDER_ERROR,
                "El servicio de IA excedio el tiempo de espera",
                "FastAPI reporto timeout del proveedor IA (HTTP " + code + ")");
    }

    private static AiServiceException providerUnavailable(int code) {
        return new AiServiceException(
                HttpStatus.SERVICE_UNAVAILABLE,
                ErrorCategory.AI_PROVIDER_ERROR,
                "El servicio de IA no esta disponible",
                "FastAPI reporto proveedor IA no disponible (HTTP " + code + ")");
    }

    private static AiServiceException fastApiCommunicationTimeout() {
        return new AiServiceException(
                HttpStatus.GATEWAY_TIMEOUT,
                ErrorCategory.FASTAPI_COMMUNICATION_ERROR,
                "El servicio de IA excedio el tiempo de espera",
                "Timeout de comunicacion HTTP hacia FastAPI");
    }

    private static AiServiceException fastApiUnreachable(ResourceAccessException exception) {
        Throwable root = exception.getMostSpecificCause();
        String causeType = root != null ? root.getClass().getSimpleName() : exception.getClass().getSimpleName();
        return new AiServiceException(
                HttpStatus.SERVICE_UNAVAILABLE,
                ErrorCategory.FASTAPI_COMMUNICATION_ERROR,
                "El servicio de IA no esta disponible",
                "No se pudo establecer conexion HTTP con FastAPI (" + causeType + ")");
    }

    private static AiServiceException invalidResponse(String detail) {
        return new AiServiceException(
                HttpStatus.BAD_GATEWAY,
                ErrorCategory.INVALID_MODEL_RESPONSE,
                "El servicio de IA no devolvio una respuesta valida",
                detail);
    }

    private static AiServiceException incompleteResponse(int code) {
        return new AiServiceException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                ErrorCategory.INVALID_MODEL_RESPONSE,
                "La IA no pudo validar o completar todas las modificaciones solicitadas. "
                        + "No se aplicaron cambios. Aclara el tipo de elemento, su nombre y la acción; "
                        + "si el tipo UML aún no está soportado por el agente, usa el editor.",
                "FastAPI rechazo o no completo el lote de operaciones (HTTP " + code + ")");
    }

    private static AiServiceException upstreamInternalError(int code) {
        return new AiServiceException(
                HttpStatus.BAD_GATEWAY,
                ErrorCategory.FASTAPI_COMMUNICATION_ERROR,
                "El servicio de IA tuvo un error interno",
                "FastAPI respondio con error interno HTTP " + code);
    }
}
