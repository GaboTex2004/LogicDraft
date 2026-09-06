package com.sw1.backend.ai.agent.service;

import com.sw1.backend.ai.agent.dto.*;
import com.sw1.backend.ai.client.AiServiceClient;
import org.springframework.stereotype.Service;

@Service
public class ContextualAgentService {
    private final AgentProjectContextService contexts;
    private final AiServiceClient client;

    public ContextualAgentService(AgentProjectContextService contexts, AiServiceClient client) {
        this.contexts = contexts;
        this.client = client;
    }

    public AgentAskResponse ask(Long projectId, AgentAskRequest request) {
        AgentProjectContext context = contexts.load(projectId, request);
        return new AgentAskResponse(client.askAgent(new AgentUpstreamRequest(request.message().strip(), context)));
    }
}
