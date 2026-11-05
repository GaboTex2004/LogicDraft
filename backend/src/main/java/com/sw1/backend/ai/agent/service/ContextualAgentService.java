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

    public void authorizeEdit(Long projectId) {
        contexts.requireEditAccess(projectId);
    }

    public AgentAskResponse ask(Long projectId, AgentAskRequest request) {
        AgentProjectContext context = contexts.load(projectId, request);
        var intent = AgentIntentClassifier.classify(request.message());
        if (intent == AgentIntentClassifier.Intent.MODIFICATION) contexts.requireEditAccess(projectId);
        AgentAskResponse proposed = client.askAgent(new AgentUpstreamRequest(
                request.message().strip(), context,
                request.conversation() == null ? java.util.List.of() : request.conversation()));
        if (intent != AgentIntentClassifier.Intent.MODIFICATION) {
            String answer = !proposed.operations().isEmpty()
                    ? "No se preparo ninguna propuesta: el mensaje original no autoriza modificaciones. Expresa la accion de forma explicita."
                    : AgentIntentClassifier.containsTechnicalOperation(proposed.answer())
                    ? "Puedo darte sugerencias en lenguaje natural, pero necesito más contexto de los requisitos del proyecto."
                    : proposed.answer();
            boolean informational = intent == AgentIntentClassifier.Intent.INFORMATIONAL;
            return new AgentAskResponse(answer, java.util.List.of(),
                    informational ? AgentAskResponse.Intent.INFORMATIONAL : AgentAskResponse.Intent.NEEDS_CLARIFICATION,
                    informational ? AgentAskResponse.Outcome.INFORMATIONAL : AgentAskResponse.Outcome.NEEDS_CLARIFICATION, 0);
        }
        if (proposed.operations().isEmpty()) {
            // Older upstreams may return tutorials for commands. Never present that as execution.
            boolean clarified = proposed.outcome() == AgentAskResponse.Outcome.NEEDS_CLARIFICATION;
            boolean satisfied = proposed.outcome() == AgentAskResponse.Outcome.ALREADY_SATISFIED;
            return new AgentAskResponse(clarified || satisfied ? proposed.answer()
                    : "No se preparo ninguna propuesta ni se aplicaron cambios. Indica los elementos y la modificacion solicitada.",
                    java.util.List.of(), satisfied ? AgentAskResponse.Intent.MUTATION : AgentAskResponse.Intent.NEEDS_CLARIFICATION,
                    satisfied ? AgentAskResponse.Outcome.ALREADY_SATISFIED : AgentAskResponse.Outcome.NEEDS_CLARIFICATION,
                    proposed.repairAttempts());
        }
        var diagramContext = context.semanticContext() != null
                ? context.semanticContext()
                : new com.sw1.backend.ai.diagram.dto.DiagramContext(
                        context.entities().stream().map(entity -> new com.sw1.backend.ai.diagram.dto.EntityDefinition(
                                entity.name(), entity.attributes())).toList(),
                        context.relationships().stream().map(relation -> new com.sw1.backend.ai.diagram.dto.RelationshipDefinition(
                                relation.sourceEntity(), relation.targetEntity(), relation.sourceCardinality(),
                                relation.targetCardinality(), relation.name(), relation.joinTableName(), relation.id())).toList(),
                        context.associations().stream().map(association ->
                                new com.sw1.backend.ai.diagram.dto.DiagramAssociationContext(association.entityName(),
                                        association.tableName(), association.endpointEntityNames(),
                                        association.structuralRelationshipIds())).toList(),
                        context.readOnlyElementNames());
        com.sw1.backend.ai.diagram.validation.SemanticOperationValidator.validate(
                request.message(), diagramContext, proposed.operations());
        var validated = com.sw1.backend.ai.diagram.validation.ContextualOperationValidator.validate(
                diagramContext,
                new com.sw1.backend.ai.diagram.dto.DiagramInterpretResponse(proposed.operations()),
                context.readOnlyElementNames());
        boolean empty = validated.operations().isEmpty();
        return new AgentAskResponse(empty ? "No se requieren cambios: las operaciones ya estan satisfechas."
                : "Propuesta de modificaciones: " + validated.operations().size()
                    + " operaciones. Revisa los cambios y confirma para aplicarlos.",
                validated.operations(), AgentAskResponse.Intent.MUTATION,
                empty ? AgentAskResponse.Outcome.ALREADY_SATISFIED : AgentAskResponse.Outcome.PROPOSAL,
                proposed.repairAttempts());
    }
}
