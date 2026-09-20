package com.sw1.backend.generator;

import com.sw1.backend.common.exception.AccesoDenegadoException;
import com.sw1.backend.generator.export.FullStackGenerator;
import com.sw1.backend.generator.flutter.FlutterGenerator;
import com.sw1.backend.generator.service.ApplicationSchemaService;
import com.sw1.backend.generator.service.FullStackGenerationService;
import com.sw1.backend.generator.schema.*;
import com.sw1.backend.generator.validation.ApplicationSchemaException;
import com.sw1.backend.proyecto.model.Proyecto;
import com.sw1.backend.proyecto.repository.ProyectoRepository;
import com.sw1.backend.workspace.model.RolWorkspace;
import com.sw1.backend.workspace.model.Workspace;
import com.sw1.backend.workspace.service.WorkspaceAccessService;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FullStackGenerationServiceTest {
    @Mock ProyectoRepository projects;
    @Mock WorkspaceAccessService access;
    @Mock ApplicationSchemaService schemas;
    FullStackGenerationService service;

    @BeforeEach
    void setUp() {
        service = new FullStackGenerationService(projects, access, schemas,
                new FullStackGenerator(new com.sw1.backend.generator.spring.SpringBootGenerator(), new FlutterGenerator()));
        Workspace workspace = new Workspace();
        workspace.setId(20L);
        Proyecto project = new Proyecto();
        project.setId(10L);
        project.setWorkspace(workspace);
        when(projects.findById(10L)).thenReturn(Optional.of(project));
    }

    @Test
    void ownerOrEditorCanGenerateFromPersistedSchema() {
        var schema = GeneratorTestSchemas.serviceSchema();
        when(schemas.preview(10L)).thenReturn(schema);
        var generated = service.generate(10L);
        assertEquals("servicios.zip", generated.fileName());
        assertTrue(generated.content().length > 100);
        verify(access).verificarRol(20L, RolWorkspace.OWNER, RolWorkspace.EDITOR);
        verify(schemas).preview(10L);
    }

    @Test
    void viewerAndExternalUsersAreRejectedBeforeReadingSchema() {
        doThrow(new AccesoDenegadoException("No permitido")).when(access)
                .verificarRol(20L, RolWorkspace.OWNER, RolWorkspace.EDITOR);
        assertThrows(AccesoDenegadoException.class, () -> service.generate(10L));
        verifyNoInteractions(schemas);
    }

    @Test
    void associativeEntitiesCanGenerateFullStackAfterFlutterSupport() {
        var schema = GeneratorTestSchemas.associativeMetadataSchema();
        when(schemas.preview(10L)).thenReturn(schema);

        var generated = service.generate(10L);

        assertEquals("academiaasociativa.zip", generated.fileName());
        assertTrue(generated.content().length > 100);
    }
}
