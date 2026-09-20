package com.sw1.backend.generator;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class SpringGenerationSecurityTest {
    @Autowired MockMvc mvc;

    @Test
    void generationRequiresJwt() throws Exception {
        mvc.perform(post("/api/proyectos/10/generator/backend"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void fullStackGenerationRequiresJwt() throws Exception {
        mvc.perform(post("/api/proyectos/10/generator/fullstack"))
                .andExpect(status().isUnauthorized());
    }
}
