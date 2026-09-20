package com.sw1.backend.ai.agent;

import com.sw1.backend.ai.agent.dto.*;
import com.sw1.backend.ai.agent.service.AgentProjectContextService;
import com.sw1.backend.ai.agent.service.ContextualAgentService;
import com.sw1.backend.ai.client.AiServiceClient;
import com.sw1.backend.ai.diagram.dto.*;
import com.sw1.backend.ai.diagram.model.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ContextualAgentServiceTest {
    @Mock AgentProjectContextService contexts;
    @Mock AiServiceClient client;

    @Test
    void revalidatesEditPermissionWithoutCallingModel() {
        new ContextualAgentService(contexts, client).authorizeEdit(10L);
        verify(contexts).requireEditAccess(10L);
        verifyNoInteractions(client);
    }

    @Test
    void asksOnlyOnceWithServerContextAndDoesNotWriteDiagram() {
        AgentAskRequest request = new AgentAskRequest("  revisa Producto  ", "producto", null, List.of());
        AgentProjectContext context = new AgentProjectContext(10L, "Tienda", 30L, "producto", null,
                List.of(), List.of(), List.of());
        when(contexts.load(10L, request)).thenReturn(context);
        when(client.askAgent(any())).thenReturn(new AgentAskResponse("El modelo es coherente."));

        AgentAskResponse result = new ContextualAgentService(contexts, client).ask(10L, request);

        assertEquals("El modelo es coherente.", result.answer());
        assertTrue(result.operations().isEmpty());
        ArgumentCaptor<AgentUpstreamRequest> sent = ArgumentCaptor.forClass(AgentUpstreamRequest.class);
        verify(client).askAgent(sent.capture());
        assertEquals("revisa Producto", sent.getValue().message());
        assertSame(context, sent.getValue().context());
        verifyNoMoreInteractions(contexts, client);
    }

    @Test
    void discardsInventedOperationsForInformationalQuestionIndependentlyOfModel() {
        AgentAskRequest request = new AgentAskRequest("¿Qué podría agregar a mi diagrama?", null, null,
                List.of(), List.of(new AgentConversationMessage("user", "¿Qué contiene?")));
        AgentProjectContext context = new AgentProjectContext(10L, "Academia", 30L, null, null,
                List.of(), List.of(), List.of());
        var invented = new DiagramOperation(DiagramOperationType.ADD_ENTITY,
                new EntityDefinition("Profesor", List.of()), null, null, null);
        when(contexts.load(10L, request)).thenReturn(context);
        when(client.askAgent(any())).thenReturn(new AgentAskResponse("ADD_ENTITY", List.of(invented)));

        AgentAskResponse result = new ContextualAgentService(contexts, client).ask(10L, request);

        assertTrue(result.operations().isEmpty());
        assertFalse(result.answer().contains("ADD_ENTITY"));
        verify(contexts, never()).requireEditAccess(anyLong());
        ArgumentCaptor<AgentUpstreamRequest> sent = ArgumentCaptor.forClass(AgentUpstreamRequest.class);
        verify(client).askAgent(sent.capture());
        assertEquals("¿Qué contiene?", sent.getValue().conversation().getFirst().text());
    }

    @Test
    void validatesAndReturnsAnActionableManyToManyOperation() {
        AgentAskRequest request = new AgentAskRequest("relaciona Alumno y Materia de muchos a muchos", null, null, List.of());
        AgentProjectContext context = new AgentProjectContext(10L, "Academia", 30L, null, null,
                List.of(new AgentProjectContext.AgentEntity("student", "Alumno", List.of()),
                        new AgentProjectContext.AgentEntity("subject", "Materia", List.of())),
                List.of(), List.of());
        var relation = new RelationshipDefinition("Alumno", "Materia", DiagramCardinality.ZERO_MANY,
                DiagramCardinality.ZERO_MANY, "materias", "alumno_materia");
        var operation = new DiagramOperation(DiagramOperationType.ADD_RELATIONSHIP,
                null, null, null, relation);
        when(contexts.load(10L, request)).thenReturn(context);
        when(client.askAgent(any())).thenReturn(new AgentAskResponse("Relacion N:M propuesta.", List.of(operation)));

        AgentAskResponse result = new ContextualAgentService(contexts, client).ask(10L, request);

        assertEquals(List.of(operation), result.operations());
        assertEquals("alumno_materia", result.operations().getFirst().relationship().joinTableName());
        verify(contexts).requireEditAccess(10L);
    }

    @Test
    void rejectsActionOperationsWhenMemberCannotEdit() {
        AgentAskRequest request = new AgentAskRequest("crea Cliente", null, null, List.of());
        AgentProjectContext context = new AgentProjectContext(10L, "Tienda", null, null, null,
                List.of(), List.of(), List.of());
        var operation = new DiagramOperation(DiagramOperationType.ADD_ENTITY,
                new EntityDefinition("Cliente", List.of()), null, null, null);
        when(contexts.load(10L, request)).thenReturn(context);
        when(client.askAgent(any())).thenReturn(new AgentAskResponse("Propuesta.", List.of(operation)));
        doThrow(new com.sw1.backend.common.exception.AccesoDenegadoException("Solo editores"))
                .when(contexts).requireEditAccess(10L);

        assertThrows(com.sw1.backend.common.exception.AccesoDenegadoException.class,
                () -> new ContextualAgentService(contexts, client).ask(10L, request));
    }

    @Test
    void validatesAssociationConversionAndKeepsExecutionOnClientAcceptancePath() {
        AgentAskRequest request = new AgentAskRequest("convierte la N:M en Inscripcion", null,
                "student-subject", List.of());
        var relationship = new AgentProjectContext.AgentRelationship("student-subject", "student", "subject",
                "Alumno", "Materia", DiagramCardinality.ZERO_MANY, DiagramCardinality.ZERO_MANY,
                "cursa", "alumno_materia");
        AgentProjectContext context = new AgentProjectContext(10L, "Academia", 30L, null, "student-subject",
                List.of(new AgentProjectContext.AgentEntity("student", "Alumno", List.of()),
                        new AgentProjectContext.AgentEntity("subject", "Materia", List.of())),
                List.of(relationship), List.of());
        var conversion = new AssociationConversionDefinition("student-subject", "Alumno", "Materia",
                "Inscripcion", List.of(new AttributeDefinition("nota", DiagramDataType.Integer, false, true)));
        var operation = new DiagramOperation(DiagramOperationType.CONVERT_MANY_TO_MANY_ASSOCIATION,
                null, null, null, null, conversion);
        when(contexts.load(10L, request)).thenReturn(context);
        when(client.askAgent(any())).thenReturn(new AgentAskResponse("Conversion propuesta.", List.of(operation)));

        AgentAskResponse result = new ContextualAgentService(contexts, client).ask(10L, request);

        assertEquals(List.of(operation), result.operations());
        verify(contexts).requireEditAccess(10L);
    }
}
