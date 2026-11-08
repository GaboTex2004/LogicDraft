package com.sw1.backend.ai.editor;

import com.sw1.backend.ai.agent.dto.AgentAskRequest;
import com.sw1.backend.ai.agent.dto.AgentAskResponse;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class EditorBarAiControllerTest {
    private final EditorBarAiService service = mock(EditorBarAiService.class);
    private MockMvc mvc;

    @BeforeEach void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new EditorBarAiController(service)).build();
    }

    @Test void dedicatedRoutePreservesAgentExplanationAndSelection() throws Exception {
        when(service.assist(eq(10L), any())).thenReturn(new AgentAskResponse("Indica el atributo", List.of(),
                AgentAskResponse.Intent.NEEDS_CLARIFICATION, AgentAskResponse.Outcome.NEEDS_CLARIFICATION, 0));
        mvc.perform(post("/api/proyectos/10/ai/diagram/assist").contentType(MediaType.APPLICATION_JSON)
                .content("{\"message\":\"Agrega un atributo\",\"selectedNodeId\":\"node-1\",\"recentEvents\":[],\"conversation\":[]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value("Indica el atributo"))
                .andExpect(jsonPath("$.operations").isEmpty())
                .andExpect(jsonPath("$.outcome").value("NEEDS_CLARIFICATION"));
        verify(service).assist(10L, new AgentAskRequest("Agrega un atributo", "node-1", null, List.of()));
    }

    @Test void invalidInputNeverReachesInference() throws Exception {
        for (String body : List.of("{\"message\":\"\",\"recentEvents\":[]}",
                "{\"message\":\"Crea Persona\"}",
                "{\"message\":\"" + "a".repeat(4001) + "\",\"recentEvents\":[]}")) {
            mvc.perform(post("/api/proyectos/10/ai/diagram/assist").contentType(MediaType.APPLICATION_JSON)
                    .content(body)).andExpect(status().isBadRequest());
        }
        verifyNoInteractions(service);
    }
}
