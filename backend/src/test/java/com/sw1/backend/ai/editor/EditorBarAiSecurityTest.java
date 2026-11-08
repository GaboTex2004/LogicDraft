package com.sw1.backend.ai.editor;

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
class EditorBarAiSecurityTest {
    @Autowired MockMvc mvc;

    @Test void dedicatedEndpointRequiresJwt() throws Exception {
        mvc.perform(post("/api/proyectos/10/ai/diagram/assist").contentType(MediaType.APPLICATION_JSON)
                .content("{\"message\":\"Crea una clase PersonaQA\",\"recentEvents\":[]}"))
                .andExpect(status().isUnauthorized());
    }
}
