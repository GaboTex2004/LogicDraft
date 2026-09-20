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
    @Test void multipleAttributesForDifferentEntitiesArePreserved() {
        var telefono = new AttributeDefinition("telefono", DiagramDataType.String, false, true);
        var codigo = new AttributeDefinition("codigo", DiagramDataType.String, false, true);
        var result = validate(addAttribute("Cliente", telefono), addAttribute("Pedido", codigo));
        assertEquals(2, result.operations().size());
        assertEquals(List.of("Cliente", "Pedido"), result.operations().stream().map(DiagramOperation::entityName).toList());
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
    @Test void twoCreationsCanBeFollowedByManyToManyInTheSameBatch() {
        var relation = new DiagramOperation(DiagramOperationType.ADD_RELATIONSHIP, null, null, null,
                new RelationshipDefinition("Alumno", "Materia", DiagramCardinality.ZERO_MANY,
                        DiagramCardinality.ZERO_MANY, "materias", "alumno_materia"));
        var result = validate(addEntity("Alumno"), addEntity("Materia"), relation);
        assertEquals(List.of(DiagramOperationType.ADD_ENTITY, DiagramOperationType.ADD_ENTITY,
                DiagramOperationType.ADD_RELATIONSHIP), result.operations().stream().map(DiagramOperation::type).toList());
    }
    @Test void invalidTailRejectsTheWholeVirtualBatchWithoutChangingContext() {
        var before = List.copyOf(context.entities());
        assertThrows(AiServiceException.class, () -> validate(
                addAttribute("Cliente", new AttributeDefinition("telefono", DiagramDataType.String, false, true)),
                addAttribute("Inexistente", new AttributeDefinition("codigo", DiagramDataType.String, false, true))));
        assertEquals(before, context.entities());
        assertEquals(List.of(id), context.entities().getFirst().attributes());
    }
    @Test void validatesAssociationConversionByRealIdAndRejectsEveryAmbiguousOrInvalidState() {
        var alumno = new EntityDefinition("Alumno", List.of(id));
        var materia = new EntityDefinition("Materia", List.of(id));
        var many = new RelationshipDefinition("Alumno", "Materia", DiagramCardinality.ZERO_MANY,
                DiagramCardinality.ONE_MANY, "cursa", "alumno_materia", "student-subject");
        var associationContext = new DiagramContext(List.of(alumno, materia), List.of(many));
        var own = new AttributeDefinition("nota", DiagramDataType.Integer, false, true);
        var valid = new DiagramOperation(DiagramOperationType.CONVERT_MANY_TO_MANY_ASSOCIATION,
                null, null, null, null, new AssociationConversionDefinition("student-subject", "Alumno",
                "Materia", "Inscripcion", List.of(own)));
        assertEquals(1, ContextualOperationValidator.validate(associationContext,
                new DiagramInterpretResponse(List.of(valid))).operations().size());

        var invented = new DiagramOperation(DiagramOperationType.CONVERT_MANY_TO_MANY_ASSOCIATION,
                null, null, null, null, new AssociationConversionDefinition("invented", "Alumno",
                "Materia", "Inscripcion", List.of()));
        assertThrows(AiServiceException.class, () -> ContextualOperationValidator.validate(associationContext,
                new DiagramInterpretResponse(List.of(invented))));
        var oneToMany = new DiagramContext(List.of(alumno, materia), List.of(new RelationshipDefinition(
                "Alumno", "Materia", DiagramCardinality.ONE_ONE, DiagramCardinality.ZERO_MANY,
                null, null, "student-subject")));
        assertThrows(AiServiceException.class, () -> ContextualOperationValidator.validate(oneToMany,
                new DiagramInterpretResponse(List.of(valid))));
        var duplicateName = new DiagramContext(List.of(alumno, materia,
                new EntityDefinition("Inscripcion", List.of(id))), List.of(many));
        assertThrows(AiServiceException.class, () -> ContextualOperationValidator.validate(duplicateName,
                new DiagramInterpretResponse(List.of(valid))));
        var ambiguous = new DiagramContext(List.of(alumno, materia), List.of(many,
                new RelationshipDefinition("Alumno", "Materia", DiagramCardinality.ONE_MANY,
                        DiagramCardinality.ZERO_MANY, "aprueba", "alumno_materia_aprueba", "second")));
        assertThrows(AiServiceException.class, () -> ContextualOperationValidator.validate(ambiguous,
                new DiagramInterpretResponse(List.of(valid))));
        var converted = new DiagramContext(List.of(alumno, materia), List.of(many), List.of(
                new DiagramAssociationContext("InscripcionAnterior", "alumno_materia", List.of("Alumno", "Materia"),
                        List.of("student-enrollment", "subject-enrollment"))));
        assertThrows(AiServiceException.class, () -> ContextualOperationValidator.validate(converted,
                new DiagramInterpretResponse(List.of(valid))));
        assertEquals(2, associationContext.entities().size());
        assertEquals(1, associationContext.relationships().size());
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
