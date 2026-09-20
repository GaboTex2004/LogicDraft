package com.sw1.backend.generator;

import com.sw1.backend.common.exception.AccesoDenegadoException;
import com.sw1.backend.common.exception.GlobalExceptionHandler;
import com.sw1.backend.generator.controller.ApplicationSchemaExceptionHandler;
import com.sw1.backend.generator.controller.FullStackGenerationController;
import com.sw1.backend.generator.service.FullStackGenerationService;
import com.sw1.backend.generator.service.GeneratedFullStack;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class FullStackGenerationControllerTest {
    FullStackGenerationService service;
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(FullStackGenerationService.class);
        mvc = MockMvcBuilders.standaloneSetup(new FullStackGenerationController(service))
                .setControllerAdvice(new GlobalExceptionHandler(), new ApplicationSchemaExceptionHandler()).build();
    }

    @Test
    void returnsDownloadableFullStackZip() throws Exception {
        when(service.generate(10L)).thenReturn(new GeneratedFullStack("peluqueria.zip", new byte[]{1, 2, 3}));
        mvc.perform(post("/api/proyectos/10/generator/fullstack"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/zip"))
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"peluqueria.zip\""))
                .andExpect(header().longValue("Content-Length", 3));
    }

    @Test
    void deniedExportRemainsForbidden() throws Exception {
        when(service.generate(10L)).thenThrow(new AccesoDenegadoException("No permitido"));
        mvc.perform(post("/api/proyectos/10/generator/fullstack"))
                .andExpect(status().isForbidden());
    }
}
