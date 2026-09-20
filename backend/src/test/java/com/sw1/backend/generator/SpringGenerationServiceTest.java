package com.sw1.backend.generator;

import com.sw1.backend.common.exception.AccesoDenegadoException;
import com.sw1.backend.generator.service.*;
import com.sw1.backend.generator.spring.SpringBootGenerator;
import com.sw1.backend.generator.schema.*;
import com.sw1.backend.generator.validation.ApplicationSchemaException;
import com.sw1.backend.proyecto.model.Proyecto;
import com.sw1.backend.proyecto.repository.ProyectoRepository;
import com.sw1.backend.workspace.model.*;
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
class SpringGenerationServiceTest {
    @Mock ProyectoRepository projects;
    @Mock WorkspaceAccessService access;
    @Mock ApplicationSchemaService schemas;
    SpringGenerationService service;
    Proyecto project;

    @BeforeEach
    void setUp() {
        service = new SpringGenerationService(projects, access, schemas, new SpringBootGenerator());
        Workspace workspace = new Workspace();
        workspace.setId(20L);
        project = new Proyecto();
        project.setId(10L);
        project.setWorkspace(workspace);
        when(projects.findById(10L)).thenReturn(Optional.of(project));
    }

    @Test
    void ownerOrEditorCanGenerateWithoutModifyingSchemaOrUsingExternalServices() {
        var schema = GeneratorTestSchemas.serviceSchema();
        String before = schema.toString();
        when(schemas.preview(10L)).thenReturn(schema);

        GeneratedBackend result = service.generate(10L);

        assertEquals("servicios-backend.zip", result.fileName());
        assertTrue(result.content().length > 100);
        assertEquals(before, schema.toString());
        verify(access).verificarRol(20L, RolWorkspace.OWNER, RolWorkspace.EDITOR);
        verify(schemas).preview(10L);
    }

    @Test
    void viewerAndExternalUsersAreRejectedBeforeSchemaIsRead() {
        for (String reason : new String[]{"VIEWER no puede exportar", "Usuario externo"}) {
            reset(access, schemas);
            doThrow(new AccesoDenegadoException(reason)).when(access)
                    .verificarRol(20L, RolWorkspace.OWNER, RolWorkspace.EDITOR);
            AccesoDenegadoException error = assertThrows(AccesoDenegadoException.class, () -> service.generate(10L));
            assertEquals(reason, error.getMessage());
            verifyNoInteractions(schemas);
        }
    }

    @Test
    void associativeEntitiesCanBeExportedAsSpringBackend() {
        var schema = GeneratorTestSchemas.associativeMetadataSchema();
        when(schemas.preview(10L)).thenReturn(schema);

        GeneratedBackend result = service.generate(10L);

        assertEquals("academiaasociativa-backend.zip", result.fileName());
        assertTrue(result.content().length > 100);
    }
}
