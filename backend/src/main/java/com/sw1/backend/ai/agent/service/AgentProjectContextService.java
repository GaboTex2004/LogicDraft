package com.sw1.backend.ai.agent.service;

import com.sw1.backend.ai.agent.dto.*;
import com.sw1.backend.ai.agent.dto.AgentProjectContext.AgentEntity;
import com.sw1.backend.ai.agent.dto.AgentProjectContext.AgentRelationship;
import com.sw1.backend.ai.client.AiServiceException;
import com.sw1.backend.ai.client.AiServiceException.ErrorCategory;
import com.sw1.backend.ai.diagram.validation.DiagramContextMapper;
import com.sw1.backend.ai.diagram.validation.DiagramContextMapper.DiagramSemanticSnapshot;
import com.sw1.backend.ai.diagram.validation.DiagramContextMapper.ReadOnlyUmlElement;
import com.sw1.backend.ai.diagram.validation.DiagramContextMapper.ReadOnlyUmlRelation;
import com.sw1.backend.common.exception.RecursoNoEncontradoException;
import com.sw1.backend.diagrama.model.Diagrama;
import com.sw1.backend.diagrama.repository.DiagramaRepository;
import com.sw1.backend.proyecto.repository.ProyectoRepository;
import com.sw1.backend.workspace.model.RolWorkspace;
import com.sw1.backend.workspace.service.WorkspaceAccessService;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AgentProjectContextService {
    private static final Logger log = LoggerFactory.getLogger(AgentProjectContextService.class);
    private static final int MAX_DESCRIPTION_LENGTH = 500;

    private final ProyectoRepository projects;
    private final DiagramaRepository diagrams;
    private final WorkspaceAccessService access;

    public AgentProjectContextService(ProyectoRepository projects, DiagramaRepository diagrams, WorkspaceAccessService access) {
        this.projects = projects;
        this.diagrams = diagrams;
        this.access = access;
    }

    @Transactional(readOnly = true)
    public AgentProjectContext load(Long projectId, AgentAskRequest request) {
        var project = projects.findById(projectId)
                .orElseThrow(() -> new RecursoNoEncontradoException("El proyecto no existe"));
        access.verificarAcceso(project.getWorkspace().getId());
        var diagram = diagrams.findByProyectoId(projectId);
        if (diagram.isEmpty()) {
            log.info("Agent UML context loaded projectId={} diagramId=none semanticEntities=0 relationships=0 associations=0 readOnlyUmlElements=0",
                    projectId);
            return new AgentProjectContext(projectId, project.getNombre(), null, null, null,
                    List.of(), List.of(), List.of(), List.copyOf(request.recentEvents()), project.getDescripcion());
        }
        try {
            return fromDiagram(projectId, project.getNombre(), project.getDescripcion(), diagram.get(), request);
        } catch (AiServiceException exception) {
            log.warn("Agent UML document rejected projectId={} diagramId={} category={} detail={}",
                    projectId, diagram.get().getId(), exception.getCategory(), exception.getTechnicalDetail());
            throw exception;
        } catch (RuntimeException exception) {
            log.warn("Semantic context converter failed projectId={} diagramId={} errorType={} reason={}",
                    projectId, diagram.get().getId(), exception.getClass().getSimpleName(), exception.getMessage());
            throw new AiServiceException(
                    HttpStatus.CONFLICT,
                    ErrorCategory.SEMANTIC_CONVERTER_ERROR,
                    "El diagrama guardado no pudo convertirse al contexto del agente",
                    exception.getClass().getSimpleName() + ": " + exception.getMessage());
        }
    }

    @Transactional(readOnly = true)
    public void requireEditAccess(Long projectId) {
        var project = projects.findById(projectId)
                .orElseThrow(() -> new RecursoNoEncontradoException("El proyecto no existe"));
        access.verificarRol(project.getWorkspace().getId(), RolWorkspace.OWNER, RolWorkspace.EDITOR);
    }

    private AgentProjectContext fromDiagram(Long projectId, String projectName, String projectDescription,
                                            Diagrama diagram, AgentAskRequest request) {
        DiagramSemanticSnapshot snapshot = DiagramContextMapper.inspectDocument(diagram.getContenido());

        List<AgentEntity> entities = new ArrayList<>(snapshot.semanticNodes().size());
        Set<String> modifiableNodeIds = new HashSet<>(snapshot.semanticNodes().size());
        for (var node : snapshot.semanticNodes()) {
            modifiableNodeIds.add(node.id());
            entities.add(new AgentEntity(node.id(), node.name(), node.attributes()));
        }

        List<AgentRelationship> relationships = new ArrayList<>(snapshot.semanticRelationships().size());
        Set<String> edgeIds = new HashSet<>(snapshot.semanticRelationships().size());
        for (var relation : snapshot.semanticRelationships()) {
            if (relation.id() != null) {
                edgeIds.add(relation.id());
            }
            relationships.add(new AgentRelationship(
                    relation.id(),
                    relation.sourceNodeId(),
                    relation.targetNodeId(),
                    relation.sourceEntity(),
                    relation.targetEntity(),
                    relation.sourceCardinality(),
                    relation.targetCardinality(),
                    relation.name(),
                    relation.joinTableName()));
        }

        String selectedNode = modifiableNodeIds.contains(request.selectedNodeId()) ? request.selectedNodeId() : null;
        String selectedEdge = edgeIds.contains(request.selectedEdgeId()) ? request.selectedEdgeId() : null;
        var associations = snapshot.diagramContext().associations().stream()
                .map(association -> new AgentProjectContext.AgentAssociation(
                        association.entityName(),
                        association.tableName(),
                        association.endpointEntityNames(),
                        association.structuralRelationshipIds()))
                .toList();

        String enrichedDescription = enrichDescriptionWithReadOnlyUml(
                projectDescription, snapshot.readOnlyElements(), snapshot.readOnlyRelations());

        log.info("Agent UML context loaded projectId={} diagramId={} version={} semanticEntities={} relationships={} associations={} readOnlyUmlElements={}",
                projectId,
                diagram.getId(),
                snapshot.version(),
                entities.size(),
                relationships.size(),
                associations.size(),
                snapshot.readOnlyElements().size());

        return new AgentProjectContext(
                projectId,
                projectName,
                diagram.getId(),
                selectedNode,
                selectedEdge,
                List.copyOf(entities),
                List.copyOf(relationships),
                associations,
                List.copyOf(request.recentEvents()),
                enrichedDescription,
                snapshot.readOnlyElementNames());
    }

    private static String enrichDescriptionWithReadOnlyUml(
            String baseDescription,
            List<ReadOnlyUmlElement> readOnlyElements,
            List<ReadOnlyUmlRelation> readOnlyRelations) {
        if (readOnlyElements.isEmpty() && readOnlyRelations.isEmpty()) {
            return baseDescription;
        }
        StringBuilder summary = new StringBuilder();
        if (baseDescription != null && !baseDescription.isBlank()) {
            summary.append(baseDescription.strip());
        }
        if (!readOnlyElements.isEmpty()) {
            if (!summary.isEmpty()) {
                summary.append(" | ");
            }
            summary.append("Elementos UML de solo consulta: ");
            List<String> parts = new ArrayList<>(readOnlyElements.size());
            for (ReadOnlyUmlElement element : readOnlyElements) {
                if ("ENUMERATION".equals(element.kind()) && !element.literals().isEmpty()) {
                    parts.add(element.kind() + " " + element.name() + " ["
                            + String.join(", ", element.literals()) + "]");
                } else if (!element.methodNames().isEmpty()) {
                    parts.add(element.kind() + " " + element.name() + " ("
                            + String.join(", ", element.methodNames()) + ")");
                } else {
                    parts.add(element.kind() + " " + element.name());
                }
            }
            summary.append(String.join("; ", parts));
        }
        if (!readOnlyRelations.isEmpty()) {
            if (!summary.isEmpty()) {
                summary.append(" | ");
            }
            summary.append("Relaciones UML estructurales: ");
            List<String> relParts = new ArrayList<>(readOnlyRelations.size());
            for (ReadOnlyUmlRelation rel : readOnlyRelations) {
                relParts.add(rel.sourceName() + " -> " + rel.targetName() + " (" + rel.relationKind() + ")");
            }
            summary.append(String.join("; ", relParts));
        }
        String combined = summary.toString();
        return combined.length() <= MAX_DESCRIPTION_LENGTH
                ? combined
                : combined.substring(0, MAX_DESCRIPTION_LENGTH - 3) + "...";
    }
}
