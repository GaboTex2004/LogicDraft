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
        AgentAskResponse proposed = client.askAgent(new AgentUpstreamRequest(
                request.message().strip(), context,
                request.conversation() == null ? java.util.List.of() : request.conversation()));
        if (intent != AgentIntentClassifier.Intent.MODIFICATION) {
            String answer = AgentIntentClassifier.containsTechnicalOperation(proposed.answer())
                    ? "Puedo darte sugerencias en lenguaje natural, pero necesito más contexto de los requisitos del proyecto."
                    : proposed.answer();
            return new AgentAskResponse(answer, java.util.List.of());
        }
        if (!proposed.operations().isEmpty()) contexts.requireEditAccess(projectId);
        var diagramContext = new com.sw1.backend.ai.diagram.dto.DiagramContext(
                context.entities().stream().map(entity -> new com.sw1.backend.ai.diagram.dto.EntityDefinition(
                        entity.name(), entity.attributes())).toList(),
                context.relationships().stream().map(relation -> new com.sw1.backend.ai.diagram.dto.RelationshipDefinition(
                        relation.sourceEntity(), relation.targetEntity(), relation.sourceCardinality(),
                        relation.targetCardinality(), relation.name(), relation.joinTableName(), relation.id())).toList(),
                context.associations().stream().map(association ->
                        new com.sw1.backend.ai.diagram.dto.DiagramAssociationContext(association.entityName(),
                                association.tableName(), association.endpointEntityNames(),
                                association.structuralRelationshipIds())).toList());
        var validated = com.sw1.backend.ai.diagram.validation.ContextualOperationValidator.validate(
                diagramContext, new com.sw1.backend.ai.diagram.dto.DiagramInterpretResponse(proposed.operations()));
        return new AgentAskResponse(proposed.answer(), validated.operations());
    }
}
