package com.sw1.backend.ai.editor;

import com.sw1.backend.ai.agent.dto.*;
import com.sw1.backend.ai.agent.service.ContextualAgentService;
import com.sw1.backend.ai.service.ProjectDiagramContextService;
import com.sw1.backend.ai.diagram.dto.*;
import com.sw1.backend.ai.diagram.model.DiagramOperationType;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EditorBarAiServiceTest {
    private final ContextualAgentService agent = mock(ContextualAgentService.class);
    private final ProjectDiagramContextService contexts = mock(ProjectDiagramContextService.class);
    private final EditorBarAiService service = new EditorBarAiService(agent, contexts);
    private final AgentAskRequest request = new AgentAskRequest("Crea una clase PersonaQA", null, null, List.of());
    private final DiagramContext empty = new DiagramContext(List.of(), List.of());

    @Test void deniedBeforeInferenceNeverCallsAgent() {
        when(contexts.load(10L)).thenThrow(new org.springframework.security.access.AccessDeniedException("VIEWER"));
        assertThrows(org.springframework.security.access.AccessDeniedException.class, () -> service.assist(10L, request));
        verifyNoInteractions(agent);
    }
    @Test void permissionRevokedDuringInferenceRejectsResponse() {
        when(contexts.load(10L)).thenReturn(empty).thenThrow(new org.springframework.security.access.AccessDeniedException("revoked"));
        when(agent.ask(10L, request)).thenReturn(new AgentAskResponse("Aclara la orden", List.of(), AgentAskResponse.Intent.NEEDS_CLARIFICATION, AgentAskResponse.Outcome.NEEDS_CLARIFICATION, 0));
        assertThrows(org.springframework.security.access.AccessDeniedException.class, () -> service.assist(10L, request));
        verify(agent, times(1)).ask(10L, request);
    }
    @Test void clarificationPreservedWithoutConversationSideEffects() {
        when(contexts.load(10L)).thenReturn(empty);
        var reply = new AgentAskResponse("Indica el nombre", List.of(), AgentAskResponse.Intent.NEEDS_CLARIFICATION, AgentAskResponse.Outcome.NEEDS_CLARIFICATION, 0);
        when(agent.ask(10L, request)).thenReturn(reply);
        assertSame(reply, service.assist(10L, request));
        verify(contexts, times(2)).load(10L);
        verify(agent).ask(10L, request);
        verifyNoMoreInteractions(agent);
    }
    @Test void validClassProposalUsesAgentAndFreshValidation() {
        when(contexts.load(10L)).thenReturn(empty);
        var operation = new DiagramOperation(DiagramOperationType.ADD_ENTITY, new EntityDefinition("PersonaQA", List.of(), "CLASS"), null, null, null);
        when(agent.ask(10L, request)).thenReturn(new AgentAskResponse("Revisa la propuesta", List.of(operation), AgentAskResponse.Intent.MUTATION, AgentAskResponse.Outcome.PROPOSAL, 0));
        assertEquals(1, service.assist(10L, request).operations().size());
        verify(contexts, times(2)).load(10L);
        verify(agent).ask(10L, request);
    }
    @Test void freshValidationRejectsUnrequestedDestructiveOperation() {
        when(contexts.load(10L)).thenReturn(empty);
        var operation = new DiagramOperation(DiagramOperationType.DELETE_ENTITY, null, "PersonaQA", null, null);
        when(agent.ask(10L, request)).thenReturn(new AgentAskResponse("Borrado", List.of(operation), AgentAskResponse.Intent.MUTATION, AgentAskResponse.Outcome.PROPOSAL, 0));
        assertThrows(com.sw1.backend.ai.client.AiServiceException.class, () -> service.assist(10L, request));
    }
    @Test void concurrentCreationRejectsDuplicateAgainstFreshContext() {
        var entity = new EntityDefinition("PersonaQA", List.of(), "CLASS");
        when(contexts.load(10L)).thenReturn(empty).thenReturn(new DiagramContext(List.of(entity), List.of()));
        var operation = new DiagramOperation(DiagramOperationType.ADD_ENTITY, entity, null, null, null);
        when(agent.ask(10L, request)).thenReturn(new AgentAskResponse("Revisa", List.of(operation),
                AgentAskResponse.Intent.MUTATION, AgentAskResponse.Outcome.PROPOSAL, 0));
        var error = assertThrows(com.sw1.backend.ai.client.AiServiceException.class,
                () -> service.assist(10L, request));
        assertEquals("Ya existe una entidad con ese nombre", error.getMessage());
    }
}
