package com.sw1.backend.ai.conversation.service;

import com.sw1.backend.ai.agent.dto.AgentConversationMessage;
import com.sw1.backend.ai.conversation.model.ConversationMessageStatus;
import com.sw1.backend.ai.conversation.model.MensajeConversacionAgente;
import com.sw1.backend.ai.conversation.model.MessageAuthorType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Bounded context window selector for FastAPI {@code /api/v1/agent/ask}.
 *
 * <p>PostgreSQL stores unlimited conversation messages, while upstream LLM requests are capped by
 * both message count ({@code <= 10}, matching FastAPI's Pydantic {@code max_length=10}) and an
 * explicit character/token budget ({@code <= 10,000} total characters, {@code <= 3,800} per turn).
 * Failed error messages and system notices are excluded so error text never pollutes prompts.
 */
public final class ConversationContextSelector {
    public static final int MAX_UPSTREAM_MESSAGES = 10;
    public static final int MAX_SINGLE_TURN_CHARS = 3_800;
    public static final int MAX_TOTAL_CONTEXT_CHARS = 10_000;

    private ConversationContextSelector() {
    }

    /**
     * Expects {@code recentDescending} ordered from newest to oldest (prior to the active prompt)
     * and returns chronological {@link AgentConversationMessage} turns (oldest to newest).
     */
    public static List<AgentConversationMessage> selectBoundedContext(
            List<MensajeConversacionAgente> recentDescending) {
        if (recentDescending == null || recentDescending.isEmpty()) {
            return List.of();
        }
        List<AgentConversationMessage> selectedReverse = new ArrayList<>();
        int accumulatedChars = 0;

        for (MensajeConversacionAgente candidate : recentDescending) {
            if (selectedReverse.size() >= MAX_UPSTREAM_MESSAGES) {
                break;
            }
            if (candidate.getEstado() != ConversationMessageStatus.SENT) {
                continue;
            }
            String role;
            String formattedText;
            if (candidate.getAutorTipo() == MessageAuthorType.USER) {
                role = "user";
                String body = candidate.getContenido() == null ? "" : candidate.getContenido().strip();
                if (body.isEmpty()) {
                    continue;
                }
                String author = candidate.getAutorNombre() == null || candidate.getAutorNombre().isBlank()
                        ? "Colaborador"
                        : candidate.getAutorNombre().strip();
                formattedText = truncate("[" + author + "]: " + body, MAX_SINGLE_TURN_CHARS);
            } else if (candidate.getAutorTipo() == MessageAuthorType.AGENT) {
                role = "agent";
                String body = candidate.getContenido() == null ? "" : candidate.getContenido().strip();
                if (body.isEmpty()) {
                    continue;
                }
                formattedText = truncate(body, MAX_SINGLE_TURN_CHARS);
            } else {
                continue;
            }

            if (accumulatedChars + formattedText.length() > MAX_TOTAL_CONTEXT_CHARS
                    && !selectedReverse.isEmpty()) {
                break;
            }
            accumulatedChars += formattedText.length();
            selectedReverse.add(new AgentConversationMessage(role, formattedText));
        }

        Collections.reverse(selectedReverse);
        return List.copyOf(selectedReverse);
    }

    private static String truncate(String text, int maxChars) {
        if (text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, maxChars - 1).stripTrailing() + "…";
    }
}
