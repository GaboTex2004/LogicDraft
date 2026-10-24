package com.sw1.backend.diagrama.service;

import com.sw1.backend.common.exception.AccesoDenegadoException;
import com.sw1.backend.diagrama.dto.request.GuardarDiagramaRequest;
import com.sw1.backend.diagrama.model.Diagrama;
import com.sw1.backend.diagrama.repository.DiagramaRepository;
import com.sw1.backend.diagrama.validation.DiagramValidationException;
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
import org.springframework.orm.ObjectOptimisticLockingFailureException;
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
        when(diagramaRepository.saveAndFlush(any(Diagrama.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.guardar(10L, request);

        ArgumentCaptor<Diagrama> captor = ArgumentCaptor.forClass(Diagrama.class);
        verify(diagramaRepository).saveAndFlush(captor.capture());
        Diagrama guardado = captor.getValue();
        assertEquals(Map.of("version", 1, "nodes", List.of(node("cliente", "Cliente")), "edges", List.of()), guardado.getContenido());
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
    void guardaYRecuperaRelacionManyToManySinPerderMetadatos() {
        GuardarDiagramaRequest request = new GuardarDiagramaRequest();
        request.setVersion(1);
        request.setNodes(List.of(node("alumno", "Alumno"), node("materia", "Materia")));
        Map<String, Object> relationData = Map.of(
                "sourceCardinality", "ZERO_MANY", "targetCardinality", "ONE_MANY",
                "name", "materias", "joinTableName", "alumno_materia");
        request.setEdges(List.of(Map.of("id", "alumno-materia", "source", "alumno",
                "target", "materia", "type", "relationship", "data", relationData)));
        when(diagramaRepository.findByProyectoId(10L)).thenReturn(Optional.empty());
        when(diagramaRepository.saveAndFlush(any(Diagrama.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.guardar(10L, request);

        ArgumentCaptor<Diagrama> captor = ArgumentCaptor.forClass(Diagrama.class);
        verify(diagramaRepository).saveAndFlush(captor.capture());
        Diagrama saved = captor.getValue();
        when(diagramaRepository.findByProyectoId(10L)).thenReturn(Optional.of(saved));
        Map<?, ?> restoredEdge = (Map<?, ?>) ((List<?>) service.obtenerPorProyecto(10L)
                .contenido().get("edges")).getFirst();
        assertEquals(relationData, restoredEdge.get("data"));
    }

    @Test
    void guardaYRecuperaMetadatosOpcionalesDeEntidadAsociativa() {
        GuardarDiagramaRequest request = new GuardarDiagramaRequest();
        request.setVersion(1);
        Map<String, Object> association = Map.of(
                "kind", "MANY_TO_MANY_ASSOCIATION", "tableName", "alumno_materia", "uniquePair", true,
                "endpoints", List.of(
                        Map.of("role", "SOURCE", "entityId", "alumno",
                                "relationshipId", "alumno-inscripcion", "foreignKeyName", "alumno_id"),
                        Map.of("role", "TARGET", "entityId", "materia",
                                "relationshipId", "materia-inscripcion", "foreignKeyName", "materia_id")));
        Map<String, Object> associationNode = node("inscripcion", "Inscripcion");
        ((Map<String, Object>) associationNode.get("data")).put("association", association);
        request.setNodes(List.of(node("alumno", "Alumno"), node("materia", "Materia"), associationNode));
        request.setEdges(List.of(
                relationship("alumno-inscripcion", "alumno", "inscripcion"),
                relationship("materia-inscripcion", "materia", "inscripcion")));
        when(diagramaRepository.findByProyectoId(10L)).thenReturn(Optional.empty());
        when(diagramaRepository.saveAndFlush(any(Diagrama.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.guardar(10L, request);

        ArgumentCaptor<Diagrama> captor = ArgumentCaptor.forClass(Diagrama.class);
        verify(diagramaRepository).saveAndFlush(captor.capture());
        Diagrama saved = captor.getValue();
        when(diagramaRepository.findByProyectoId(10L)).thenReturn(Optional.of(saved));
        Map<?, ?> restoredNode = (Map<?, ?>) ((List<?>) service.obtenerPorProyecto(10L).contenido().get("nodes")).get(2);
        Map<?, ?> restoredData = (Map<?, ?>) restoredNode.get("data");
        assertEquals(association, restoredData.get("association"));
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
        verify(diagramaRepository, never()).saveAndFlush(any(Diagrama.class));
    }

    private GuardarDiagramaRequest requestValido() {
        GuardarDiagramaRequest request = new GuardarDiagramaRequest();
        request.setVersion(1);
        request.setNodes(List.of(node("cliente", "Cliente")));
        request.setEdges(List.of());
        return request;
    }

    @Test
    void rechazaDocumentoConReferenciasRotasAntesDePersistir() {
        GuardarDiagramaRequest request = requestValido();
        request.setEdges(List.of(relationship("r1", "cliente", "inexistente")));
        assertThrows(DiagramValidationException.class, () -> service.guardar(10L, request));
        verify(diagramaRepository, never()).saveAndFlush(any(Diagrama.class));
    }

    @Test
    void rechazaRevisionObsoletaYConservaDocumentoConfirmado() {
        Diagrama existing = new Diagrama();
        existing.setProyecto(proyecto);
        existing.setVersion(1);
        existing.setRevision(4L);
        existing.setContenido(Map.of("version", 1, "nodes", List.of(), "edges", List.of()));
        when(diagramaRepository.findByProyectoId(10L)).thenReturn(Optional.of(existing));
        GuardarDiagramaRequest request = requestValido();
        request.setBaseRevision(3L);

        DiagramConflictException conflict = assertThrows(DiagramConflictException.class,
                () -> service.guardar(10L, request));

        assertEquals(4L, conflict.getCurrentRevision());
        assertEquals(List.of(), existing.getContenido().get("nodes"));
        verify(diagramaRepository, never()).saveAndFlush(any(Diagrama.class));
    }

    @Test
    void actualizaSoloConLaRevisionBaseConfirmada() {
        Diagrama existing = new Diagrama();
        existing.setProyecto(proyecto);
        existing.setVersion(1);
        existing.setRevision(4L);
        existing.setContenido(Map.of("version", 1, "nodes", List.of(), "edges", List.of()));
        when(diagramaRepository.findByProyectoId(10L)).thenReturn(Optional.of(existing));
        when(diagramaRepository.saveAndFlush(existing)).thenReturn(existing);
        GuardarDiagramaRequest request = requestValido();
        request.setBaseRevision(4L);

        service.guardar(10L, request);

        verify(diagramaRepository).saveAndFlush(existing);
    }

    @Test
    void traduceUnaColisionAtomicaDeDosGuardadosAConflicto() {
        Diagrama existing = new Diagrama();
        existing.setProyecto(proyecto);
        existing.setVersion(1);
        existing.setRevision(4L);
        existing.setContenido(Map.of("version", 1, "nodes", List.of(), "edges", List.of()));
        when(diagramaRepository.findByProyectoId(10L)).thenReturn(Optional.of(existing));
        when(diagramaRepository.saveAndFlush(existing)).thenThrow(
                new ObjectOptimisticLockingFailureException(Diagrama.class, 1L));
        GuardarDiagramaRequest request = requestValido();
        request.setBaseRevision(4L);

        assertThrows(DiagramConflictException.class, () -> service.guardar(10L, request));
    }

    private static Map<String, Object> node(String id, String name) {
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("id", id);
        data.put("name", name);
        data.put("attributes", List.of());
        Map<String, Object> node = new java.util.LinkedHashMap<>();
        node.put("id", id);
        node.put("type", "entity");
        node.put("position", Map.of("x", 0, "y", 0));
        node.put("data", data);
        return node;
    }

    private static Map<String, Object> relationship(String id, String source, String target) {
        return Map.of("id", id, "source", source, "target", target, "type", "relationship",
                "data", Map.of("sourceCardinality", "ONE_ONE", "targetCardinality", "ZERO_MANY"));
    }
}
