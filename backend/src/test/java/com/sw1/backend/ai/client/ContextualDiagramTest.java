package com.sw1.backend.ai.client;

import com.sw1.backend.ai.diagram.dto.*;
import com.sw1.backend.ai.diagram.model.*;
import com.sw1.backend.ai.diagram.validation.*;
import com.sw1.backend.ai.dto.request.AiGenerateRequest;
import com.sw1.backend.ai.service.*;
import com.sw1.backend.common.exception.*;
import com.sw1.backend.diagrama.repository.DiagramaRepository;
import com.sw1.backend.proyecto.repository.ProyectoRepository;
import com.sw1.backend.proyecto.model.Proyecto;
import com.sw1.backend.workspace.model.*;
import com.sw1.backend.workspace.service.WorkspaceAccessService;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ContextualDiagramTest {
    AttributeDefinition id = new AttributeDefinition("id", DiagramDataType.Long, true, false);
    DiagramContext context = new DiagramContext(List.of(
        new EntityDefinition("Cliente", List.of(id)), new EntityDefinition("Pedido", List.of())), List.of());

    DiagramOperation addAttribute(String entity, AttributeDefinition attribute) {
        return new DiagramOperation(DiagramOperationType.ADD_ATTRIBUTE, null, entity, attribute, null);
    }
    DiagramOperation addEntity(String name) {
        return new DiagramOperation(DiagramOperationType.ADD_ENTITY, new EntityDefinition(name, List.of(id)), null, null, null);
    }
    DiagramOperation relation(String source, String target) {
        return new DiagramOperation(DiagramOperationType.ADD_RELATIONSHIP, null, null, null,
            new RelationshipDefinition(source, target, DiagramCardinality.ONE_ONE, DiagramCardinality.ZERO_MANY));
    }
    DiagramInterpretResponse validate(DiagramOperation... operations) {
        return ContextualOperationValidator.validate(context, new DiagramInterpretResponse(List.of(operations)));
    }
    @Test void existingEntityAcceptsNewAttribute() {
        assertEquals(1, validate(addAttribute("CLIENTE", new AttributeDefinition("telefono", DiagramDataType.String, false, true))).operations().size());
    }
    @Test void newEntityAccepted() { assertEquals(1, validate(addEntity("Producto")).operations().size()); }
    @Test void validRelationshipAccepted() { assertEquals(1, validate(relation("Cliente", "Pedido")).operations().size()); }
    @Test void missingReferencesRejected() {
        assertThrows(AiServiceException.class, () -> validate(addAttribute("Persona", id)));
        assertThrows(AiServiceException.class, () -> validate(relation("Cliente", "Persona")));
    }
    @Test void duplicateEntityRejected() { assertThrows(AiServiceException.class, () -> validate(addEntity("CLIENTE"))); }
    @Test void identicalAttributeIsNoop() { assertTrue(validate(addAttribute("cliente", id)).operations().isEmpty()); }
    @Test void conflictingAttributeRejected() {
        for (var a : List.of(new AttributeDefinition("id", DiagramDataType.String, true, false),
                new AttributeDefinition("id", DiagramDataType.Long, false, false),
                new AttributeDefinition("id", DiagramDataType.Long, true, true)))
            assertThrows(AiServiceException.class, () -> validate(addAttribute("Cliente", a)));
    }
    @Test void duplicateRelationIsNoop() {
        var r = relation("Cliente", "Pedido");
        var existing = new DiagramContext(context.entities(), List.of(r.relationship()));
        assertTrue(ContextualOperationValidator.validate(existing, new DiagramInterpretResponse(List.of(r))).operations().isEmpty());
        assertEquals(1, validate(r, r).operations().size());
    }
    @Test void earlierCreationsCanBeReferencedWithoutMutation() {
        assertEquals(2, validate(addEntity("Producto"), relation("Cliente", "Producto")).operations().size());
        assertEquals(2, context.entities().size());
        assertThrows(AiServiceException.class, () -> validate(relation("Cliente", "Producto"), addEntity("Producto")));
    }
    @Test void projectsAndPermissionsAreCheckedBeforeDiagram() {
        var projects = mock(ProyectoRepository.class);
        var diagrams = mock(DiagramaRepository.class);
        var access = mock(WorkspaceAccessService.class);
        var loader = new ProjectDiagramContextService(projects, diagrams, access);
        when(projects.findById(1L)).thenReturn(Optional.empty());
        assertThrows(RecursoNoEncontradoException.class, () -> loader.load(1L));
        verifyNoInteractions(diagrams, access);
        var workspace = new Workspace(); workspace.setId(8L);
        var project = new Proyecto(); project.setWorkspace(workspace);
        when(projects.findById(1L)).thenReturn(Optional.of(project));
        doThrow(new AccesoDenegadoException("No permitido")).when(access).verificarRol(8L, RolWorkspace.OWNER, RolWorkspace.EDITOR);
        assertThrows(AccesoDenegadoException.class, () -> loader.load(1L));
        verifyNoInteractions(diagrams);
    }
    @Test void contextIsPassedAndFreshStateValidated() {
        var loader = mock(ProjectDiagramContextService.class);
        var client = mock(AiServiceClient.class);
        when(loader.load(1L)).thenReturn(context);
        var expected = new ContextualInterpretRequest("Crea Producto", context);
        when(client.interpret(expected)).thenReturn(new DiagramInterpretResponse(List.of(addEntity("Producto"))));
        assertEquals(1, new ContextualDiagramAiService(loader, client).interpret(1L, new AiGenerateRequest("Crea Producto")).operations().size());
        verify(client).interpret(expected);
        verify(loader, times(2)).load(1L);
    }
    @Test void rejectedAccessNeverCallsAI() {
        var loader = mock(ProjectDiagramContextService.class);
        var client = mock(AiServiceClient.class);
        when(loader.load(1L)).thenThrow(new AccesoDenegadoException("No permitido"));
        assertThrows(AccesoDenegadoException.class, () -> new ContextualDiagramAiService(loader, client).interpret(1L, new AiGenerateRequest("hola")));
        verifyNoInteractions(client);
    }
    @Test void realDocumentProjectionOmitsUIAndDefaultsUnspecifiedCardinality() {
        var node = Map.of("id", "n1", "position", Map.of("x", 10, "y", 20),
            "data", Map.of("name", "Cliente", "attributes", List.of(Map.of("name", "id", "type", "BIGINT", "primaryKey", true))));
        var document = Map.<String, Object>of("version", 1, "nodes", List.of(node),
            "edges", List.of(Map.of("id", "e1", "source", "n1", "target", "n1", "type", "smoothstep")));
        var projected = DiagramContextMapper.fromDocument(document);
        assertEquals(List.of(id), projected.entities().get(0).attributes());
        assertEquals(DiagramCardinality.ONE_ONE, projected.relationships().get(0).sourceCardinality());
        assertFalse(projected.toString().contains("position"));
    }
    @Test void invalidDiagramRejected() {
        assertThrows(AiServiceException.class, () -> DiagramContextMapper.fromDocument(Map.of("version", 2)));
        assertThrows(AiServiceException.class, () -> DiagramContextMapper.fromDocument(Map.of("version", 1, "nodes", List.of(),
            "edges", List.of(Map.of("source", "missing", "target", "missing")))));
    }
}
