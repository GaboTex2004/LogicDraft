package com.sw1.backend.diagrama.service;

import com.sw1.backend.common.exception.AccesoDenegadoException;
import com.sw1.backend.diagrama.dto.request.GuardarDiagramaRequest;
import com.sw1.backend.diagrama.model.Diagrama;
import com.sw1.backend.diagrama.repository.DiagramaRepository;
import com.sw1.backend.proyecto.model.Proyecto;
import com.sw1.backend.proyecto.repository.ProyectoRepository;
import com.sw1.backend.workspace.model.RolWorkspace;
import com.sw1.backend.workspace.model.Workspace;
import com.sw1.backend.workspace.service.WorkspaceAccessService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DiagramaServiceTest {

    @Mock private DiagramaRepository diagramaRepository;
    @Mock private ProyectoRepository proyectoRepository;
    @Mock private WorkspaceAccessService workspaceAccessService;
    private DiagramaService service;
    private Proyecto proyecto;

    @BeforeEach
    void preparar() {
        service = new DiagramaService(diagramaRepository, proyectoRepository, workspaceAccessService);
        Workspace workspace = new Workspace();
        workspace.setId(20L);
        proyecto = new Proyecto();
        proyecto.setId(10L);
        proyecto.setWorkspace(workspace);
        when(proyectoRepository.findById(10L)).thenReturn(Optional.of(proyecto));
    }

    @Test
    void ownerPuedeGuardarYCargarContenidoEquivalente() {
        GuardarDiagramaRequest request = requestValido();
        when(diagramaRepository.findByProyectoId(10L)).thenReturn(Optional.empty());
        when(diagramaRepository.save(any(Diagrama.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.guardar(10L, request);

        ArgumentCaptor<Diagrama> captor = ArgumentCaptor.forClass(Diagrama.class);
        verify(diagramaRepository).save(captor.capture());
        Diagrama guardado = captor.getValue();
        assertEquals(Map.of("version", 1, "nodes", List.of(Map.of("id", "cliente")), "edges", List.of()), guardado.getContenido());
        verify(workspaceAccessService).verificarRol(20L, RolWorkspace.OWNER, RolWorkspace.EDITOR);

        when(diagramaRepository.findByProyectoId(10L)).thenReturn(Optional.of(guardado));
        assertEquals(guardado.getContenido(), service.obtenerPorProyecto(10L).contenido());
        verify(workspaceAccessService).verificarAcceso(20L);
    }

    @Test
    void usuarioSinAccesoNoPuedeLeerDiagrama() {
        doThrow(new AccesoDenegadoException("Sin acceso")).when(workspaceAccessService).verificarAcceso(20L);
        assertThrows(AccesoDenegadoException.class, () -> service.obtenerPorProyecto(10L));
        verify(diagramaRepository, never()).findByProyectoId(10L);
    }

    @Test
    void viewerPuedeLeerPeroNoGuardar() {
        Diagrama diagrama = new Diagrama();
        diagrama.setProyecto(proyecto);
        diagrama.setVersion(1);
        diagrama.setContenido(Map.of("version", 1, "nodes", List.of(), "edges", List.of()));
        when(diagramaRepository.findByProyectoId(10L)).thenReturn(Optional.of(diagrama));

        service.obtenerPorProyecto(10L);

        doThrow(new AccesoDenegadoException("Rol insuficiente")).when(workspaceAccessService)
                .verificarRol(20L, RolWorkspace.OWNER, RolWorkspace.EDITOR);
        assertThrows(AccesoDenegadoException.class, () -> service.guardar(10L, requestValido()));
        verify(diagramaRepository, never()).save(any(Diagrama.class));
    }

    private GuardarDiagramaRequest requestValido() {
        GuardarDiagramaRequest request = new GuardarDiagramaRequest();
        request.setVersion(1);
        request.setNodes(List.of(Map.of("id", "cliente")));
        request.setEdges(List.of());
        return request;
    }
}
