package com.sw1.backend.ai.agent;

import com.sw1.backend.ai.agent.dto.*;
import com.sw1.backend.ai.agent.service.AgentProjectContextService;
import com.sw1.backend.common.exception.AccesoDenegadoException;
import com.sw1.backend.diagrama.model.Diagrama;
import com.sw1.backend.diagrama.repository.DiagramaRepository;
import com.sw1.backend.proyecto.model.Proyecto;
import com.sw1.backend.proyecto.repository.ProyectoRepository;
import com.sw1.backend.workspace.model.Workspace;
import com.sw1.backend.workspace.service.WorkspaceAccessService;
import jakarta.validation.Validation;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AgentProjectContextServiceTest {
    @Mock ProyectoRepository projects;
    @Mock DiagramaRepository diagrams;
    @Mock WorkspaceAccessService access;
    AgentProjectContextService service;
    Proyecto project;

    @BeforeEach
    void setUp() {
        service = new AgentProjectContextService(projects, diagrams, access);
        Workspace workspace = new Workspace();
        workspace.setId(20L);
        project = new Proyecto();
        project.setId(10L);
        project.setNombre("Tienda");
        project.setDescripcion("Gestión de catálogo y ventas");
        project.setWorkspace(workspace);
    }

    @Test
    void memberIncludingViewerGetsPersistedNodesRelationshipsAndSelection() {
        when(projects.findById(10L)).thenReturn(Optional.of(project));
        Diagrama diagram = persistedDiagram();
        when(diagrams.findByProyectoId(10L)).thenReturn(Optional.of(diagram));
        AgentAskRequest request = request("producto", "rel-1", List.of(event(AgentEventType.NODE_SELECTED)));

        AgentProjectContext context = service.load(10L, request);

        verify(access).verificarAcceso(20L);
        verifyNoMoreInteractions(access);
        assertEquals(30L, context.diagramId());
        assertEquals("Gestión de catálogo y ventas", context.projectDescription());
        assertEquals(List.of("Categoria", "Producto"), context.entities().stream().map(AgentProjectContext.AgentEntity::name).toList());
        assertEquals("producto", context.selectedNodeId());
        assertEquals("rel-1", context.selectedEdgeId());
        assertEquals(1, context.relationships().size());
        var relation = context.relationships().getFirst();
        assertEquals("Categoria", relation.sourceEntity());
        assertEquals("Producto", relation.targetEntity());
        assertEquals("ONE_ONE", relation.sourceCardinality().name());
        assertEquals("ZERO_MANY", relation.targetCardinality().name());
        assertEquals(1, context.recentEvents().size());
    }

    @Test
    void unknownSelectionIsSafelyIgnored() {
        when(projects.findById(10L)).thenReturn(Optional.of(project));
        when(diagrams.findByProyectoId(10L)).thenReturn(Optional.of(persistedDiagram()));
        AgentProjectContext context = service.load(10L, request("foreign-node", "foreign-edge", List.of()));
        assertNull(context.selectedNodeId());
        assertNull(context.selectedEdgeId());
    }

    @Test
    void externalUserIsRejectedBeforeDiagramIsRead() {
        when(projects.findById(10L)).thenReturn(Optional.of(project));
        doThrow(new AccesoDenegadoException("Sin acceso")).when(access).verificarAcceso(20L);
        assertThrows(AccesoDenegadoException.class, () -> service.load(10L, request(null, null, List.of())));
        verifyNoInteractions(diagrams);
    }

    @Test
    void projectWithoutDiagramProducesValidEmptyContext() {
        when(projects.findById(10L)).thenReturn(Optional.of(project));
        when(diagrams.findByProyectoId(10L)).thenReturn(Optional.empty());
        AgentProjectContext context = service.load(10L, request("fake", "fake", List.of()));
        assertNull(context.diagramId());
        assertNull(context.selectedNodeId());
        assertNull(context.selectedEdgeId());
        assertTrue(context.entities().isEmpty());
        assertTrue(context.relationships().isEmpty());
    }

    @Test
    void requestRejectsMoreThan25EventsAndMissingEventFields() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            List<AgentEventInput> tooMany = new ArrayList<>();
            for (int i = 0; i < 26; i++) tooMany.add(event(AgentEventType.NODE_UPDATED));
            assertFalse(validator.validate(request(null, null, tooMany)).isEmpty());
            AgentEventInput invalid = new AgentEventInput(null, null, null, null);
            assertFalse(validator.validate(request(null, null, List.of(invalid))).isEmpty());
        }
    }

    private static AgentAskRequest request(String nodeId, String edgeId, List<AgentEventInput> events) {
        return new AgentAskRequest("¿Está bien este modelo?", nodeId, edgeId, events);
    }

    private static AgentEventInput event(AgentEventType type) {
        return new AgentEventInput(type, "producto", null, Instant.parse("2026-09-05T12:00:00Z"));
    }

    private Diagrama persistedDiagram() {
        Map<String, Object> categoryData = Map.of("name", "Categoria", "attributes", List.of(
                Map.of("name", "ID", "type", "INTEGER", "primaryKey", true)));
        Map<String, Object> productData = Map.of("name", "Producto", "attributes", List.of(
                Map.of("name", "Precio", "type", "DECIMAL", "primaryKey", false)));
        Map<String, Object> relationData = Map.of("sourceCardinality", "ONE_ONE", "targetCardinality", "ZERO_MANY");
        Map<String, Object> content = Map.of(
                "version", 1,
                "nodes", List.of(
                        Map.of("id", "categoria", "position", Map.of("x", 10, "y", 20), "data", categoryData),
                        Map.of("id", "producto", "position", Map.of("x", 30, "y", 40), "data", productData)),
                "edges", List.of(Map.of("id", "rel-1", "source", "categoria", "target", "producto", "data", relationData)));
        Diagrama diagram = new Diagrama();
        diagram.setId(30L);
        diagram.setProyecto(project);
        diagram.setVersion(1);
        diagram.setContenido(content);
        return diagram;
    }
}
