package com.sw1.backend.ai.conversation.service;

import com.sw1.backend.ai.conversation.dto.ConversationMessageResponse;
import com.sw1.backend.ai.conversation.dto.ConversationSummaryResponse;
import com.sw1.backend.ai.conversation.dto.SharedProposalResponse;
import com.sw1.backend.collaboration.dto.CollaborationEvent;
import com.sw1.backend.collaboration.model.CollaborationEventType;
import com.sw1.backend.usuario.model.Usuario;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Publishes shared conversation, message, AI indicator, and proposal events over the existing
 * project STOMP topic strictly after PostgreSQL transaction commit.
 */
@Component
public class AgentConversationEventPublisher {
    private static final Logger log = LoggerFactory.getLogger(AgentConversationEventPublisher.class);

    private final ApplicationEventPublisher events;
    private final SimpMessagingTemplate messaging;

    public AgentConversationEventPublisher(
            ApplicationEventPublisher events,
            SimpMessagingTemplate messaging) {
        this.events = events;
        this.messaging = messaging;
    }

    public void scheduleConversationCreated(
            ConversationSummaryResponse conversation,
            Usuario actor,
            String clientId) {
        CollaborationEvent event = new CollaborationEvent(
                "conv-created:" + conversation.id(),
                CollaborationEventType.CONVERSATION_CREATED,
                actor.getId(),
                actor.getNombre(),
                conversation.projectId(),
                clientId,
                Instant.now(),
                Map.of("conversation", conversation),
                null);
        events.publishEvent(new CommittedConversationEvent(event));
    }

    public void scheduleConversationUpdated(
            ConversationSummaryResponse conversation,
            Usuario actor,
            String clientId) {
        CollaborationEvent event = new CollaborationEvent(
                "conv-updated:" + conversation.id() + ":" + UUID.randomUUID(),
                CollaborationEventType.CONVERSATION_UPDATED,
                actor.getId(),
                actor.getNombre(),
                conversation.projectId(),
                clientId,
                Instant.now(),
                Map.of("conversation", conversation),
                null);
        events.publishEvent(new CommittedConversationEvent(event));
    }

    public void scheduleMessageCreated(
            ConversationMessageResponse message,
            Long actorUserId,
            String actorName,
            String clientId) {
        CollaborationEvent event = new CollaborationEvent(
                "conv-msg:" + message.id(),
                CollaborationEventType.CONVERSATION_MESSAGE_CREATED,
                actorUserId,
                actorName,
                message.projectId(),
                clientId,
                Instant.now(),
                Map.of(
                        "conversationId", message.conversationId(),
                        "message", message),
                null);
        events.publishEvent(new CommittedConversationEvent(event));
    }

    public void scheduleAiStatus(
            Long projectId,
            Long conversationId,
            boolean active,
            Usuario requestedBy,
            String errorMessage,
            String clientId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("conversationId", conversationId);
        payload.put("active", active);
        payload.put("requestedByUserId", requestedBy.getId());
        payload.put("requestedByName", requestedBy.getNombre());
        if (errorMessage != null && !errorMessage.isBlank()) {
            payload.put("error", errorMessage);
        }
        CollaborationEvent event = new CollaborationEvent(
                "conv-ai-status:" + conversationId + ":" + UUID.randomUUID(),
                CollaborationEventType.CONVERSATION_AI_STATUS,
                requestedBy.getId(),
                requestedBy.getNombre(),
                projectId,
                clientId,
                Instant.now(),
                payload,
                null);
        events.publishEvent(new CommittedConversationEvent(event));
    }

    public void scheduleProposalUpdated(
            SharedProposalResponse proposal,
            Long versionLock,
            Usuario actor,
            String clientId) {
        long safeVersion = versionLock == null ? 0L : versionLock;
        CollaborationEvent event = new CollaborationEvent(
                "conv-proposal:" + proposal.id() + ":" + safeVersion + ":" + proposal.estado(),
                CollaborationEventType.CONVERSATION_PROPOSAL_UPDATED,
                actor.getId(),
                actor.getNombre(),
                proposal.projectId(),
                clientId,
                Instant.now(),
                Map.of(
                        "conversationId", proposal.conversationId(),
                        "messageId", proposal.messageId(),
                        "proposal", proposal),
                null);
        events.publishEvent(new CommittedConversationEvent(event));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void afterCommit(CommittedConversationEvent committed) {
        try {
            CollaborationEvent event = committed.notification();
            messaging.convertAndSend("/topic/proyectos/" + event.projectId(), event);
        } catch (RuntimeException failure) {
            log.warn("Conversation notification could not be delivered for project {}",
                    committed.notification().projectId());
        }
    }

    public record CommittedConversationEvent(CollaborationEvent notification) {
    }
}
