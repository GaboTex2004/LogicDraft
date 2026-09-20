package com.sw1.backend.ai.agent;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ProjectAgentSecurityTest {
    @Autowired MockMvc mvc;

    @Test
    void endpointRequiresJwt() throws Exception {
        mvc.perform(post("/api/proyectos/10/agent/ask")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"message\":\"revisa\",\"selectedNodeId\":null,\"selectedEdgeId\":null,\"recentEvents\":[]}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void editAuthorizationEndpointRequiresJwt() throws Exception {
        mvc.perform(post("/api/proyectos/10/agent/authorize-edit"))
                .andExpect(status().isUnauthorized());
    }
}
