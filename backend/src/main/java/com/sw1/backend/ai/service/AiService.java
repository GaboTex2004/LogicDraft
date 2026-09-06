package com.sw1.backend.ai.service;

import com.sw1.backend.ai.client.AiServiceClient;
import com.sw1.backend.ai.dto.request.AiGenerateRequest;
import com.sw1.backend.ai.dto.response.AiGenerateResponse;
import org.springframework.stereotype.Service;

@Service
public class AiService {
    private final AiServiceClient client;

    public AiService(AiServiceClient client) {
        this.client = client;
    }

    public AiGenerateResponse generate(AiGenerateRequest request) {
        return client.generate(request);
    }

    public com.sw1.backend.ai.diagram.dto.DiagramInterpretResponse interpret(AiGenerateRequest request) {
        return client.interpret(request);
    }
}
