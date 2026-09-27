package com.sw1.backend.generator.ea;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sw1.backend.diagrama.dto.request.GuardarDiagramaRequest;
import com.sw1.backend.diagrama.service.DiagramaService;
import com.sw1.backend.proyecto.dto.request.CrearProyectoRequest;
import com.sw1.backend.proyecto.dto.response.ProyectoResponse;
import com.sw1.backend.proyecto.service.ProyectoService;
import java.io.IOException;
import java.io.InputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class EnterpriseArchitectImportServiceTest {
    @Mock ProyectoService proyectos;
    @Mock DiagramaService diagramas;

    @Test
    void nonBlockingWarningsStillAllowProjectAndDiagramCreation() throws IOException {
        byte[] xmi;
        try (InputStream input = getClass().getResourceAsStream("/generator/ea/ExamenVentas.xmi")) {
            assertNotNull(input);
            xmi = input.readAllBytes();
        }
        EnterpriseArchitectXmiReader reader = new EnterpriseArchitectXmiReader(new EnterpriseArchitectXmiParser());
        ProyectoResponse created = new ProyectoResponse();
        created.setId(42L);
        created.setNombre("Starter Class Diagram");
        when(proyectos.crear(any(CrearProyectoRequest.class))).thenReturn(created);
        EnterpriseArchitectImportService service = new EnterpriseArchitectImportService(reader, proyectos, diagramas);

        ProyectoResponse result = service.importar(7L, xmi);

        assertEquals(42L, result.getId());
        ArgumentCaptor<CrearProyectoRequest> project = ArgumentCaptor.forClass(CrearProyectoRequest.class);
        verify(proyectos).crear(project.capture());
        assertEquals("Starter Class Diagram", project.getValue().getNombre());
        assertEquals(7L, project.getValue().getWorkspaceId());
        ArgumentCaptor<GuardarDiagramaRequest> document = ArgumentCaptor.forClass(GuardarDiagramaRequest.class);
        verify(diagramas).guardar(eq(42L), document.capture());
        assertEquals(4, document.getValue().getNodes().size());
        assertEquals(3, document.getValue().getEdges().size());
    }
}
