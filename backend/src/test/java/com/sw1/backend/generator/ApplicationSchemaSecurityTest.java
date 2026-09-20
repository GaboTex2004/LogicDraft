package com.sw1.backend.generator;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ApplicationSchemaSecurityTest {
    @Autowired MockMvc mvc;

    @Test
    void previewRequiresJwt() throws Exception {
        mvc.perform(get("/api/proyectos/10/generator/schema"))
                .andExpect(status().isUnauthorized());
    }
}
