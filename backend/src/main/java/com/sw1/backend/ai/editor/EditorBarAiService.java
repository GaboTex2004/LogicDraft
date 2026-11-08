package com.sw1.backend.ai.editor;

import com.sw1.backend.ai.agent.dto.AgentAskRequest;
import com.sw1.backend.ai.agent.dto.AgentAskResponse;
import com.sw1.backend.ai.agent.service.ContextualAgentService;
import com.sw1.backend.ai.service.ProjectDiagramContextService;
import com.sw1.backend.ai.diagram.dto.DiagramInterpretResponse;
import com.sw1.backend.ai.diagram.validation.ContextualOperationValidator;
import com.sw1.backend.ai.diagram.validation.SemanticOperationValidator;
import org.springframework.stereotype.Service;

/** Stateless editor adapter. Does not create conversations or persist proposals/diagrams. */
@Service
public class EditorBarAiService {
    private final ContextualAgentService agent;
    private final ProjectDiagramContextService contexts;

    public EditorBarAiService(ContextualAgentService agent, ProjectDiagramContextService contexts) {
        this.agent = agent;
        this.contexts = contexts;
    }

    public AgentAskResponse assist(Long projectId, AgentAskRequest request) {
        contexts.load(projectId); // Preserve the editor bar's OWNER/EDITOR boundary before inference.
        var response = agent.ask(projectId, request);
        var latest = contexts.load(projectId); // Recheck permission and current persisted state afterwards.
        if (response.operations().isEmpty()) return response;
        SemanticOperationValidator.validate(request.message(), latest, response.operations());
        var validated = ContextualOperationValidator.validate(latest,
                new DiagramInterpretResponse(response.operations()), latest.readOnlyElementNames());
        if (validated.operations().isEmpty()) {
            return new AgentAskResponse("Los cambios ya están satisfechos; no hay modificaciones pendientes.",
                    java.util.List.of(), AgentAskResponse.Intent.MUTATION, AgentAskResponse.Outcome.ALREADY_SATISFIED, response.repairAttempts());
        }
        return new AgentAskResponse(response.answer(), validated.operations(), response.intent(), response.outcome(), response.repairAttempts());
    }
}
