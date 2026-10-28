package com.sw1.backend.ai.conversation.service;

import com.sw1.backend.ai.conversation.exception.ConversationValidationException;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Detects explicit {@code @LogicDraft} mentions in shared conversation messages and extracts a
 * clean prompt for intent classification and upstream FastAPI execution without altering the
 * human message persisted in PostgreSQL.
 */
public final class AgentMentionParser {
    private static final Pattern MENTION_TOKEN = Pattern.compile(
            "(?i)(?:^|(?<=\\s))@logicdraft\\b[,:;\\-]*");
    private static final Pattern LEADING_MENTION = Pattern.compile(
            "(?i)^\\s*@logicdraft\\b[,:;\\-\\s]*");

    private AgentMentionParser() {
    }

    public static boolean containsExplicitMention(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        return MENTION_TOKEN.matcher(message).find();
    }

    public static boolean shouldInvokeAgent(String message, boolean invokeAgentFlag) {
        return invokeAgentFlag || containsExplicitMention(message);
    }

    public static String extractPromptForAgent(String rawMessage) {
        if (rawMessage == null) {
            throw new ConversationValidationException("El mensaje para el agente no puede estar vacío");
        }
        String withoutLeading = LEADING_MENTION.matcher(rawMessage).replaceFirst("");
        Matcher inlineMatcher = MENTION_TOKEN.matcher(withoutLeading);
        String cleaned = inlineMatcher.replaceAll(" ").replaceAll("[ \\t]{2,}", " ").strip();
        if (cleaned.isEmpty()) {
            throw new ConversationValidationException(
                    "Escribe una consulta o instrucción junto a @LogicDraft");
        }
        return cleaned;
    }
}
