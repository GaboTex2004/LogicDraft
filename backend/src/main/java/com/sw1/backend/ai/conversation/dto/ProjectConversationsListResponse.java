package com.sw1.backend.ai.conversation.dto;

import java.util.List;

public record ProjectConversationsListResponse(
        Long projectId,
        boolean canEdit,
        List<ConversationSummaryResponse> conversations
) {
    public ProjectConversationsListResponse {
        conversations = conversations == null ? List.of() : List.copyOf(conversations);
    }
}
