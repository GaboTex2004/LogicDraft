package com.sw1.backend.collaboration.controller;

import com.sw1.backend.collaboration.dto.CollaborationClientEvent;
import com.sw1.backend.collaboration.dto.CollaborationEvent;
import com.sw1.backend.collaboration.model.CollaborationEventType;
import com.sw1.backend.collaboration.security.CollaborationIdentity;
import com.sw1.backend.collaboration.security.ProjectCollaborationAuthorizer;
import com.sw1.backend.collaboration.service.ElementLockedException;
import com.sw1.backend.collaboration.service.ProjectElementLockService;
import com.sw1.backend.collaboration.service.ProjectPresenceService;
import com.sw1.backend.common.exception.AccesoDenegadoException;
import com.sw1.backend.diagrama.validation.DiagramDocumentValidator;
import com.sw1.backend.diagrama.validation.DiagramValidationException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;

import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@Controller
public class CollaborationController {
    private static final Pattern CLIENT_ID_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{1,100}$");
    private static final double MAX_COORDINATE = 100_000.0;
    private static final int MAX_ELEMENT_ID_LENGTH = 120;
    private static final int MAX_NODE_PAYLOAD_BYTES = 64_000;
    private static final int MAX_EDGE_PAYLOAD_BYTES = 32_000;
    private static final int MAX_BATCH_PAYLOAD_BYTES = 1_000_000;
    static final long MIN_CURSOR_INTERVAL_NANOS = 25_000_000L;

    private final ProjectCollaborationAuthorizer authorizer;
    private final SimpMessagingTemplate messagingTemplate;
    private final ProjectPresenceService presenceService;
    private final ProjectElementLockService lockService;

    @Autowired
    public CollaborationController(
            ProjectCollaborationAuthorizer authorizer,
            SimpMessagingTemplate messagingTemplate,
            ProjectPresenceService presenceService,
            ProjectElementLockService lockService) {
        this.authorizer = authorizer;
        this.messagingTemplate = messagingTemplate;
        this.presenceService = presenceService;
        this.lockService = lockService;
    }

    public CollaborationController(
            ProjectCollaborationAuthorizer authorizer,
            SimpMessagingTemplate messagingTemplate,
            ProjectPresenceService presenceService) {
        this(
                authorizer,
                messagingTemplate,
                presenceService,
                new ProjectElementLockService(
                        messagingTemplate,
                        Clock.systemUTC(),
                        ProjectElementLockService.DEFAULT_LEASE_TTL,
                        false));
    }

    public CollaborationController(
            ProjectCollaborationAuthorizer authorizer,
            SimpMessagingTemplate messagingTemplate) {
        this(authorizer, messagingTemplate, new ProjectPresenceService(messagingTemplate));
    }

    @MessageMapping("/proyectos/{projectId}/eventos")
    public void publish(
            @DestinationVariable Long projectId,
            @Payload CollaborationClientEvent request,
            Principal principal,
            @Header(name = "simpSessionId", required = false) String sessionId) {
        if (request == null || request.type() == null) {
            authorizer.authorize(projectId, principal);
            throw new MessageDeliveryException("Evento de colaboración inválido");
        }

        switch (request.type()) {
            case PING -> publishPing(projectId, request, principal);
            case LOCK_SESSION_BIND -> bindLockSession(projectId, request, principal, sessionId);
            case CURSOR_MOVED -> publishCursorMoved(projectId, request, principal, sessionId);
            case CURSOR_LEFT -> publishCursorLeft(projectId, request, principal, sessionId);
            case NODE_CREATED, NODE_MOVED, NODE_UPDATED, NODE_DELETED,
                    EDGE_CREATED, EDGE_UPDATED, EDGE_DELETED, DIAGRAM_BATCH_APPLIED ->
                    publishLiveDiagramEvent(projectId, request, principal, sessionId);
            case USER_JOINED, USER_LEFT, ELEMENT_LOCKED, ELEMENT_UNLOCKED,
                    DIAGRAM_SAVED, DIAGRAM_COMMITTED,
                    CONVERSATION_CREATED, CONVERSATION_UPDATED,
                    CONVERSATION_MESSAGE_CREATED, CONVERSATION_AI_STATUS,
                    CONVERSATION_PROPOSAL_UPDATED -> {
                authorizer.authorize(projectId, principal);
                throw new MessageDeliveryException("Tipo de evento reservado al servidor");
            }
        }
    }

    private void bindLockSession(
            Long projectId,
            CollaborationClientEvent request,
            Principal principal,
            String sessionId) {
        CollaborationIdentity identity = authorizer.authorize(projectId, principal);
        String clientId = requireClientId(request.clientId());
        String effectiveSessionId = resolveSessionId(sessionId, identity.userId(), clientId);
        lockService.bindSession(projectId, identity.userId(), clientId, effectiveSessionId);
    }

    private void publishPing(Long projectId, CollaborationClientEvent request, Principal principal) {
        CollaborationIdentity identity = authorizer.authorize(projectId, principal);
        if (request.message() != null && request.message().length() > 200) {
            throw new MessageDeliveryException("PING demasiado largo");
        }
        CollaborationEvent event = CollaborationEvent.create(
                CollaborationEventType.PING, identity.userId(), identity.name(), projectId,
                null, null, request.message());
        messagingTemplate.convertAndSend("/topic/proyectos/" + projectId, event);
    }

    private void publishCursorMoved(
            Long projectId,
            CollaborationClientEvent request,
            Principal principal,
            String sessionId) {
        CollaborationIdentity identity = authorizer.authorize(projectId, principal);
        String clientId = requireClientId(request.clientId());
        Map<String, Object> payload = requirePayload(request.payload());
        double x = requireCoordinate(payload.get("x"), "x");
        double y = requireCoordinate(payload.get("y"), "y");
        String effectiveSessionId = resolveSessionId(sessionId, identity.userId(), clientId);
        lockService.bindSession(projectId, identity.userId(), clientId, effectiveSessionId);
        if (!presenceService.tryRecordCursorMove(
                effectiveSessionId, projectId, identity, clientId, MIN_CURSOR_INTERVAL_NANOS)) {
            return;
        }
        CollaborationEvent event = CollaborationEvent.create(
                CollaborationEventType.CURSOR_MOVED,
                identity.userId(),
                identity.name(),
                projectId,
                clientId,
                Map.of("x", x, "y", y, "sessionId", effectiveSessionId),
                null);
        messagingTemplate.convertAndSend("/topic/proyectos/" + projectId, event);
    }

    private void publishCursorLeft(
            Long projectId,
            CollaborationClientEvent request,
            Principal principal,
            String sessionId) {
        CollaborationIdentity identity = authorizer.authorize(projectId, principal);
        String clientId = requireClientId(request.clientId());
        String effectiveSessionId = resolveSessionId(sessionId, identity.userId(), clientId);
        presenceService.clearCursor(effectiveSessionId, projectId);
        CollaborationEvent event = CollaborationEvent.create(
                CollaborationEventType.CURSOR_LEFT,
                identity.userId(),
                identity.name(),
                projectId,
                clientId,
                Map.of("sessionId", effectiveSessionId),
                null);
        messagingTemplate.convertAndSend("/topic/proyectos/" + projectId, event);
    }

    private void publishLiveDiagramEvent(
            Long projectId,
            CollaborationClientEvent request,
            Principal principal,
            String sessionId) {
        CollaborationIdentity identity;
        try {
            identity = authorizer.authorizeEditor(projectId, principal);
        } catch (AccesoDenegadoException denied) {
            throw new MessageDeliveryException(denied.getMessage());
        }
        String clientId = requireClientId(request.clientId());
        Map<String, Object> rawPayload = requirePayload(request.payload());
        String effectiveSessionId = resolveSessionId(sessionId, identity.userId(), clientId);
        lockService.bindSession(projectId, identity.userId(), clientId, effectiveSessionId);

        Map<String, Object> sanitized = switch (request.type()) {
            case NODE_MOVED -> sanitizeNodeMoved(rawPayload, effectiveSessionId);
            case NODE_DELETED -> Map.of("nodeId", requireElementId(rawPayload.get("nodeId"), "nodeId"));
            case EDGE_DELETED -> Map.of("edgeId", requireElementId(rawPayload.get("edgeId"), "edgeId"));
            case NODE_CREATED, NODE_UPDATED -> sanitizeNodePayload(rawPayload);
            case EDGE_CREATED, EDGE_UPDATED -> sanitizeEdgePayload(rawPayload);
            case DIAGRAM_BATCH_APPLIED -> sanitizeBatchPayload(rawPayload);
            default -> throw new MessageDeliveryException("Tipo de evento no soportado");
        };

        List<String> lockedCheckIds = extractAffectedIdsForPreview(request.type(), rawPayload, sanitized);
        try {
            lockService.verifyCanModify(projectId, lockedCheckIds, identity.userId(), clientId);
        } catch (ElementLockedException lockedByOther) {
            // Never relay unauthorized previews onto elements locked by another collaborator
            return;
        }

        CollaborationEvent event = CollaborationEvent.create(
                request.type(),
                identity.userId(),
                identity.name(),
                projectId,
                clientId,
                sanitized,
                null);
        messagingTemplate.convertAndSend("/topic/proyectos/" + projectId, event);
    }

    private static List<String> extractAffectedIdsForPreview(
            CollaborationEventType type,
            Map<String, Object> rawPayload,
            Map<String, Object> sanitized) {
        return switch (type) {
            case NODE_MOVED, NODE_DELETED -> List.of((String) sanitized.get("nodeId"));
            case EDGE_DELETED -> List.of((String) sanitized.get("edgeId"));
            case NODE_CREATED, NODE_UPDATED -> {
                Map<?, ?> node = (Map<?, ?>) sanitized.get("node");
                yield List.of((String) node.get("id"));
            }
            case EDGE_CREATED -> {
                Map<?, ?> edge = (Map<?, ?>) sanitized.get("edge");
                yield List.of((String) edge.get("id"), (String) edge.get("source"), (String) edge.get("target"));
            }
            case EDGE_UPDATED -> {
                Map<?, ?> edge = (Map<?, ?>) sanitized.get("edge");
                yield List.of((String) edge.get("id"));
            }
            case DIAGRAM_BATCH_APPLIED -> {
                if (rawPayload.get("affectedElementIds") instanceof List<?> rawList) {
                    List<String> ids = new ArrayList<>();
                    for (Object item : rawList) {
                        if (item instanceof String text && !text.isBlank() && text.length() <= MAX_ELEMENT_ID_LENGTH) {
                            ids.add(text);
                        }
                    }
                    yield ids;
                }
                yield List.of();
            }
            default -> List.of();
        };
    }

    private static Map<String, Object> sanitizeNodeMoved(Map<String, Object> payload, String sessionId) {
        String nodeId = requireElementId(payload.get("nodeId"), "nodeId");
        if (!(payload.get("position") instanceof Map<?, ?> position)) {
            throw new MessageDeliveryException("Posición de nodo inválida");
        }
        double x = requireCoordinate(position.get("x"), "position.x");
        double y = requireCoordinate(position.get("y"), "position.y");
        boolean dragging = Boolean.TRUE.equals(payload.get("dragging"));
        return Map.of(
                "nodeId", nodeId,
                "position", Map.of("x", x, "y", y),
                "dragging", dragging,
                "sessionId", sessionId);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> sanitizeNodePayload(Map<String, Object> payload) {
        if (!(payload.get("node") instanceof Map<?, ?> rawNode)) {
            throw new MessageDeliveryException("Payload de nodo inválido");
        }
        requireBoundedSize(rawNode, MAX_NODE_PAYLOAD_BYTES, "nodo");
        String id = requireElementId(rawNode.get("id"), "node.id");
        if (!(rawNode.get("position") instanceof Map<?, ?> position)) {
            throw new MessageDeliveryException("Posición de nodo inválida");
        }
        requireCoordinate(position.get("x"), "node.position.x");
        requireCoordinate(position.get("y"), "node.position.y");
        if (!(rawNode.get("data") instanceof Map<?, ?> data)) {
            throw new MessageDeliveryException("Datos de nodo inválidos");
        }
        String dataId = requireElementId(data.get("id"), "node.data.id");
        if (!id.equals(dataId)) {
            throw new MessageDeliveryException("El ID visual y semántico del nodo no coinciden");
        }
        if (!(data.get("name") instanceof String name) || name.isBlank() || name.length() > MAX_ELEMENT_ID_LENGTH) {
            throw new MessageDeliveryException("Nombre de nodo inválido");
        }
        if (!(data.get("attributes") instanceof List<?>)) {
            throw new MessageDeliveryException("Atributos de nodo inválidos");
        }
        return Map.of("node", (Map<String, Object>) rawNode);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> sanitizeEdgePayload(Map<String, Object> payload) {
        if (!(payload.get("edge") instanceof Map<?, ?> rawEdge)) {
            throw new MessageDeliveryException("Payload de relación inválido");
        }
        requireBoundedSize(rawEdge, MAX_EDGE_PAYLOAD_BYTES, "relación");
        requireElementId(rawEdge.get("id"), "edge.id");
        String source = requireElementId(rawEdge.get("source"), "edge.source");
        String target = requireElementId(rawEdge.get("target"), "edge.target");
        if (source.equals(target)) {
            throw new MessageDeliveryException("Las autorrelaciones no están soportadas");
        }
        return Map.of("edge", (Map<String, Object>) rawEdge);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> sanitizeBatchPayload(Map<String, Object> payload) {
        if (!(payload.get("document") instanceof Map<?, ?> rawDocument)) {
            throw new MessageDeliveryException("Documento de lote colaborativo inválido");
        }
        requireBoundedSize(rawDocument, MAX_BATCH_PAYLOAD_BYTES, "documento");
        Map<String, Object> document = (Map<String, Object>) rawDocument;
        try {
            DiagramDocumentValidator.validate(document);
        } catch (DiagramValidationException | IllegalArgumentException invalid) {
            throw new MessageDeliveryException("El lote colaborativo contiene una estructura UML inválida");
        }
        return Map.of("document", document);
    }

    private static void requireBoundedSize(Object value, int maxBytes, String label) {
        if (value.toString().getBytes(StandardCharsets.UTF_8).length > maxBytes) {
            throw new MessageDeliveryException("El payload de " + label + " supera el tamaño permitido");
        }
    }

    private static String requireClientId(String clientId) {
        if (clientId == null || !CLIENT_ID_PATTERN.matcher(clientId).matches()) {
            throw new MessageDeliveryException("clientId inválido");
        }
        return clientId;
    }

    private static Map<String, Object> requirePayload(Map<String, Object> payload) {
        if (payload == null) {
            throw new MessageDeliveryException("El evento requiere payload");
        }
        return payload;
    }

    private static String requireElementId(Object value, String field) {
        if (!(value instanceof String text) || text.isBlank() || text.length() > MAX_ELEMENT_ID_LENGTH) {
            throw new MessageDeliveryException("Identificador inválido en " + field);
        }
        return text;
    }

    private static double requireCoordinate(Object value, String field) {
        if (!(value instanceof Number number)) {
            throw new MessageDeliveryException("Coordenada inválida en " + field);
        }
        double coordinate = number.doubleValue();
        if (!Double.isFinite(coordinate) || coordinate < -MAX_COORDINATE || coordinate > MAX_COORDINATE) {
            throw new MessageDeliveryException("Coordenada fuera de rango en " + field);
        }
        return coordinate;
    }

    private static String resolveSessionId(String sessionId, Long userId, String clientId) {
        return (sessionId != null && !sessionId.isBlank())
                ? sessionId
                : ("client:" + userId + ":" + clientId);
    }
}
