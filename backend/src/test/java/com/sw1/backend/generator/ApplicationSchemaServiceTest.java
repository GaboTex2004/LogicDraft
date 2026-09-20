package com.sw1.backend.generator;

import com.sw1.backend.common.exception.AccesoDenegadoException;
import com.sw1.backend.diagrama.model.Diagrama;
import com.sw1.backend.diagrama.repository.DiagramaRepository;
import com.sw1.backend.generator.service.ApplicationSchemaService;
import com.sw1.backend.proyecto.model.Proyecto;
import com.sw1.backend.proyecto.repository.ProyectoRepository;
import com.sw1.backend.workspace.model.Workspace;
import com.sw1.backend.workspace.service.WorkspaceAccessService;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ApplicationSchemaServiceTest {
    @Mock ProyectoRepository projects;
    @Mock DiagramaRepository diagrams;
    @Mock WorkspaceAccessService access;
    ApplicationSchemaService service;
    Proyecto project;
    Diagrama diagram;

    @BeforeEach
    void setUp() {
        service = new ApplicationSchemaService(projects, diagrams, access);
        Workspace workspace = new Workspace();
        workspace.setId(20L);
        project = new Proyecto();
        project.setId(10L);
        project.setNombre("Peluqueria");
        project.setWorkspace(workspace);
        Map<String, Object> field = Map.of("id", "field-id", "name", "ID", "type", "INTEGER",
                "primaryKey", true, "nullable", false);
        Map<String, Object> node = Map.of("id", "entity-id", "position", Map.of("x", 1, "y", 2),
                "data", Map.of("name", "Servicio", "attributes", List.of(field)));
        diagram = new Diagrama();
        diagram.setId(30L);
        diagram.setProyecto(project);
        diagram.setVersion(1);
        diagram.setContenido(Map.of("version", 1, "nodes", List.of(node), "edges", List.of()));
    }

    @Test
    void authorizedMemberGetsReadOnlySchemaWithoutExternalServices() {
        when(projects.findById(10L)).thenReturn(Optional.of(project));
        when(diagrams.findByProyectoId(10L)).thenReturn(Optional.of(diagram));
        Map<String, Object> before = diagram.getContenido();

        var schema = service.preview(10L);

        assertEquals("Peluqueria", schema.projectName());
        assertSame(before, diagram.getContenido());
        verify(access).verificarAcceso(20L);
        verify(diagrams, never()).save(any());
        verifyNoMoreInteractions(access, diagrams, projects);
    }

    @Test
    void externalUserIsRejectedBeforeDiagramRead() {
        when(projects.findById(10L)).thenReturn(Optional.of(project));
        doThrow(new AccesoDenegadoException("Sin acceso")).when(access).verificarAcceso(20L);

        assertThrows(AccesoDenegadoException.class, () -> service.preview(10L));

        verifyNoInteractions(diagrams);
    }
}
