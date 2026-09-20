package com.sw1.backend.ai.agent.service;

import com.sw1.backend.ai.agent.dto.*;
import com.sw1.backend.ai.agent.dto.AgentProjectContext.AgentEntity;
import com.sw1.backend.ai.agent.dto.AgentProjectContext.AgentRelationship;
import com.sw1.backend.ai.diagram.validation.DiagramContextMapper;
import com.sw1.backend.ai.client.AiServiceException;
import com.sw1.backend.common.exception.RecursoNoEncontradoException;
import com.sw1.backend.diagrama.model.Diagrama;
import com.sw1.backend.diagrama.repository.DiagramaRepository;
import com.sw1.backend.proyecto.repository.ProyectoRepository;
import com.sw1.backend.workspace.service.WorkspaceAccessService;
import com.sw1.backend.workspace.model.RolWorkspace;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AgentProjectContextService {
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
            return new AgentProjectContext(projectId, project.getNombre(), null, null, null,
                    List.of(), List.of(), List.of(), List.copyOf(request.recentEvents()), project.getDescripcion());
        }
        try {
            return fromDiagram(projectId, project.getNombre(), project.getDescripcion(), diagram.get(), request);
        } catch (AiServiceException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new AiServiceException(HttpStatus.CONFLICT,
                    "El diagrama guardado no es valido para el agente contextual");
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
        var semantic = DiagramContextMapper.fromDocument(diagram.getContenido());
        List<?> rawNodes = list(diagram.getContenido().get("nodes"));
        List<?> rawEdges = list(diagram.getContenido().get("edges"));
        List<AgentEntity> entities = new ArrayList<>();
        Map<String, String> namesById = new HashMap<>();
        for (int i = 0; i < semantic.entities().size(); i++) {
            String id = text(map(rawNodes.get(i)).get("id"));
            var entity = semantic.entities().get(i);
            namesById.put(id, entity.name());
            entities.add(new AgentEntity(id, entity.name(), entity.attributes()));
        }
        List<AgentRelationship> relationships = new ArrayList<>();
        Set<String> edgeIds = new HashSet<>();
        for (int i = 0; i < semantic.relationships().size(); i++) {
            Map<?, ?> edge = map(rawEdges.get(i));
            String id = optionalText(edge.get("id"));
            if (id != null) edgeIds.add(id);
            String source = text(edge.get("source"));
            String target = text(edge.get("target"));
            var relation = semantic.relationships().get(i);
            relationships.add(new AgentRelationship(id, source, target, namesById.get(source), namesById.get(target),
                    relation.sourceCardinality(), relation.targetCardinality(), relation.name(), relation.joinTableName()));
        }
        String selectedNode = namesById.containsKey(request.selectedNodeId()) ? request.selectedNodeId() : null;
        String selectedEdge = edgeIds.contains(request.selectedEdgeId()) ? request.selectedEdgeId() : null;
        var associations = semantic.associations().stream().map(association ->
                new AgentProjectContext.AgentAssociation(association.entityName(), association.tableName(),
                        association.endpointEntityNames(), association.structuralRelationshipIds())).toList();
        return new AgentProjectContext(projectId, projectName, diagram.getId(), selectedNode, selectedEdge,
                List.copyOf(entities), List.copyOf(relationships), associations,
                List.copyOf(request.recentEvents()), projectDescription);
    }

    private static Map<?, ?> map(Object value) {
        if (!(value instanceof Map<?, ?> result)) throw new IllegalArgumentException("Diagrama invalido");
        return result;
    }
    private static List<?> list(Object value) {
        if (!(value instanceof List<?> result)) throw new IllegalArgumentException("Diagrama invalido");
        return result;
    }
    private static String text(Object value) {
        if (!(value instanceof String result) || result.isBlank() || result.length() > 100)
            throw new IllegalArgumentException("Diagrama invalido");
        return result;
    }
    private static String optionalText(Object value) {
        return value == null ? null : text(value);
    }
}
