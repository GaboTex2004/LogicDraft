package com.sw1.backend.ai.service;

import com.sw1.backend.ai.client.AiServiceClient;
import com.sw1.backend.ai.diagram.dto.*;
import com.sw1.backend.ai.diagram.validation.ContextualOperationValidator;
import com.sw1.backend.ai.dto.request.AiGenerateRequest;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.sw1.backend.ai.client.AiServiceException;
import com.sw1.backend.ai.diagram.validation.DiagramContextMapper;
import org.springframework.http.HttpStatus;

@Service
public class ContextualDiagramAiService {
    private static final Logger log = LoggerFactory.getLogger(ContextualDiagramAiService.class);
    private final ProjectDiagramContextService context;
    private final AiServiceClient client;

    public ContextualDiagramAiService(ProjectDiagramContextService context, AiServiceClient client) {
        this.context = context;
        this.client = client;
    }

    public DiagramInterpretResponse interpret(Long projectId, AiGenerateRequest request) {
        DiagramContext initial = context.load(projectId);
        DiagramSelection selection = validateSelection(initial, request.selection());
        DiagramInterpretResponse response = client.interpret(new ContextualInterpretRequest(
                request.prompt(), initial, selection));
        // Fresh short read transaction and permission check after slow inference.
        DiagramInterpretResponse validated = ContextualOperationValidator.validate(context.load(projectId), response);
        log.info("Diagram AI batch stage=spring_validated count={} types={}", validated.operations().size(),
                validated.operations().stream().map(operation -> operation.type().name()).toList());
        return validated;
    }

    private static DiagramSelection validateSelection(DiagramContext context, DiagramSelection selection) {
        if (selection == null) return null;
        if ("ENTITY".equals(selection.kind())) {
            var matches = context.entities().stream().filter(entity ->
                    DiagramContextMapper.key(entity.name()).equals(DiagramContextMapper.key(required(selection.entityName())))).toList();
            if (matches.size() != 1) throw invalidSelection();
            return new DiagramSelection("ENTITY", matches.getFirst().name(), null, null, null);
        }
        if ("RELATIONSHIP".equals(selection.kind())) {
            String source = required(selection.sourceEntity()), target = required(selection.targetEntity());
            if (DiagramContextMapper.key(source).equals(DiagramContextMapper.key(target))) throw invalidSelection();
            var matches = context.relationships().stream().filter(relation ->
                    java.util.Set.of(DiagramContextMapper.key(relation.sourceEntity()), DiagramContextMapper.key(relation.targetEntity()))
                            .equals(java.util.Set.of(DiagramContextMapper.key(source), DiagramContextMapper.key(target))))
                    .filter(relation -> selection.relationshipName() == null
                            || relation.name() != null && DiagramContextMapper.key(relation.name())
                                    .equals(DiagramContextMapper.key(selection.relationshipName())))
                    .toList();
            if (matches.size() != 1) throw invalidSelection();
            var relation = matches.getFirst();
            return new DiagramSelection("RELATIONSHIP", null, relation.sourceEntity(), relation.targetEntity(), relation.name());
        }
        throw invalidSelection();
    }

    private static String required(String value) {
        if (value == null || value.isBlank() || !value.equals(value.strip()) || value.length() > 100) throw invalidSelection();
        return value;
    }

    private static AiServiceException invalidSelection() {
        return new AiServiceException(HttpStatus.CONFLICT, "La seleccion del editor ya no existe o es ambigua");
    }
}
