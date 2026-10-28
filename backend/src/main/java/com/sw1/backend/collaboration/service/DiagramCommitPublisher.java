package com.sw1.backend.collaboration.service;

import com.sw1.backend.auth.service.UsuarioActualService;
import com.sw1.backend.collaboration.dto.CollaborationEvent;
import com.sw1.backend.collaboration.model.CollaborationEventType;
import com.sw1.backend.diagrama.dto.response.DiagramaResponse;
import java.time.Instant;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** Notifications only: the authorized REST read remains the source of diagram content. */
@Component
public class DiagramCommitPublisher {
    private static final Logger log = LoggerFactory.getLogger(DiagramCommitPublisher.class);
    private final ApplicationEventPublisher events;
    private final UsuarioActualService users;
    private final SimpMessagingTemplate messaging;

    public DiagramCommitPublisher(ApplicationEventPublisher events, UsuarioActualService users,
                                  SimpMessagingTemplate messaging) {
        this.events = events;
        this.users = users;
        this.messaging = messaging;
    }

    public void schedule(DiagramaResponse saved, String clientId) {
        var user = users.obtenerUsuarioActual();
        // One identity per confirmed record revision, not per broker delivery or retry.
        var notification = new CollaborationEvent(
                "diagram:" + saved.id() + ":" + saved.revision(), CollaborationEventType.DIAGRAM_COMMITTED,
                user.getId(), user.getNombre(), saved.proyectoId(), clientId, Instant.now(),
                Map.of("diagramId", saved.id(), "revision", saved.revision()), null);
        events.publishEvent(new CommittedDiagram(notification));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void afterCommit(CommittedDiagram committed) {
        try {
            var event = committed.notification();
            messaging.convertAndSend("/topic/proyectos/" + event.projectId(), event);
        } catch (RuntimeException failure) {
            // A broker failure cannot turn an already committed save into an HTTP failure.
            // Clients reconcile through REST on reconnect/focus and periodic checks.
            log.warn("Diagram commit notification could not be delivered for project {}",
                    committed.notification().projectId());
        }
    }

    public record CommittedDiagram(CollaborationEvent notification) {}
}
