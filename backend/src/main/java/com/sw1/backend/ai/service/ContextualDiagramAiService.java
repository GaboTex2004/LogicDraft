package com.sw1.backend.ai.service;

import com.sw1.backend.ai.client.AiServiceClient;
import com.sw1.backend.ai.diagram.dto.*;
import com.sw1.backend.ai.diagram.validation.ContextualOperationValidator;
import com.sw1.backend.ai.dto.request.AiGenerateRequest;
import org.springframework.stereotype.Service;

@Service
public class ContextualDiagramAiService {
    private final ProjectDiagramContextService context;
    private final AiServiceClient client;

    public ContextualDiagramAiService(ProjectDiagramContextService context, AiServiceClient client) {
        this.context = context;
        this.client = client;
    }

    public DiagramInterpretResponse interpret(Long projectId, AiGenerateRequest request) {
        DiagramContext initial = context.load(projectId);
        DiagramInterpretResponse response = client.interpret(new ContextualInterpretRequest(request.prompt(), initial));
        // Fresh short read transaction and permission check after slow inference.
        return ContextualOperationValidator.validate(context.load(projectId), response);
    }
}
