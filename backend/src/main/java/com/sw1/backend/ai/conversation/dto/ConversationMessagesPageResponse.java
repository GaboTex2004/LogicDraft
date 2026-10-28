package com.sw1.backend.ai.conversation.dto;

import java.util.List;

public record ConversationMessagesPageResponse(
        Long conversationId,
        boolean hasMore,
        Long nextBeforeId,
        List<ConversationMessageResponse> messages
) {
    public ConversationMessagesPageResponse {
        messages = messages == null ? List.of() : List.copyOf(messages);
    }
}
