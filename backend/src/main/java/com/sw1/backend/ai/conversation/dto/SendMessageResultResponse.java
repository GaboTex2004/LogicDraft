package com.sw1.backend.ai.conversation.dto;

public record SendMessageResultResponse(
        ConversationMessageResponse userMessage,
        ConversationMessageResponse agentMessage,
        String agentError
) {
}
