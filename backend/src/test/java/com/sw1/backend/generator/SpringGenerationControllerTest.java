package com.sw1.backend.generator;

import com.sw1.backend.common.exception.AccesoDenegadoException;
import com.sw1.backend.common.exception.GlobalExceptionHandler;
import com.sw1.backend.generator.controller.*;
import com.sw1.backend.generator.service.*;
import com.sw1.backend.generator.spring.SpringGeneratorException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class SpringGenerationControllerTest {
    SpringGenerationService service;
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(SpringGenerationService.class);
        mvc = MockMvcBuilders.standaloneSetup(new SpringGenerationController(service))
                .setControllerAdvice(new GlobalExceptionHandler(), new ApplicationSchemaExceptionHandler()).build();
    }

    @Test
    void returnsZipWithSafeDownloadHeaders() throws Exception {
        when(service.generate(10L)).thenReturn(new GeneratedBackend("peluqueria-backend.zip", new byte[]{1, 2, 3}));
        mvc.perform(post("/api/proyectos/10/generator/backend"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/zip"))
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"peluqueria-backend.zip\""))
                .andExpect(header().longValue("Content-Length", 3));
    }

    @Test
    void accessDeniedRemainsForbidden() throws Exception {
        when(service.generate(10L)).thenThrow(new AccesoDenegadoException("No tienes el rol requerido"));
        mvc.perform(post("/api/proyectos/10/generator/backend"))
                .andExpect(status().isForbidden());
    }

    @Test
    void unsafeOrUnsupportedGenerationReturnsControlledConflict() throws Exception {
        when(service.generate(10L)).thenThrow(new SpringGeneratorException("Nombre PostgreSQL reservado"));
        mvc.perform(post("/api/proyectos/10/generator/backend"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.mensaje").value("Nombre PostgreSQL reservado"));
    }
}
