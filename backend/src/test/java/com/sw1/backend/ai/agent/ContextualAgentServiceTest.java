package com.sw1.backend.ai.agent;

import com.sw1.backend.ai.agent.dto.*;
import com.sw1.backend.ai.agent.service.AgentProjectContextService;
import com.sw1.backend.ai.agent.service.ContextualAgentService;
import com.sw1.backend.ai.client.AiServiceClient;
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
    void asksOnlyOnceWithServerContextAndDoesNotWriteDiagram() {
        AgentAskRequest request = new AgentAskRequest("  revisa Producto  ", "producto", null, List.of());
        AgentProjectContext context = new AgentProjectContext(10L, "Tienda", 30L, "producto", null,
                List.of(), List.of(), List.of());
        when(contexts.load(10L, request)).thenReturn(context);
        when(client.askAgent(any())).thenReturn("El modelo es coherente.");

        AgentAskResponse result = new ContextualAgentService(contexts, client).ask(10L, request);

        assertEquals("El modelo es coherente.", result.answer());
        ArgumentCaptor<AgentUpstreamRequest> sent = ArgumentCaptor.forClass(AgentUpstreamRequest.class);
        verify(client).askAgent(sent.capture());
        assertEquals("revisa Producto", sent.getValue().message());
        assertSame(context, sent.getValue().context());
        verifyNoMoreInteractions(contexts, client);
    }
}
