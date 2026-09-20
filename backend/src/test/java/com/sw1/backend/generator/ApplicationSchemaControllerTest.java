package com.sw1.backend.generator;

import com.sw1.backend.common.exception.AccesoDenegadoException;
import com.sw1.backend.common.exception.GlobalExceptionHandler;
import com.sw1.backend.generator.controller.ApplicationSchemaController;
import com.sw1.backend.generator.controller.ApplicationSchemaExceptionHandler;
import com.sw1.backend.generator.schema.ApplicationSchema;
import com.sw1.backend.generator.schema.ApplicationEntity;
import com.sw1.backend.generator.schema.ApplicationField;
import com.sw1.backend.generator.schema.CanonicalType;
import com.sw1.backend.generator.service.ApplicationSchemaService;
import com.sw1.backend.generator.validation.ApplicationSchemaException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ApplicationSchemaControllerTest {
    ApplicationSchemaService service;
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(ApplicationSchemaService.class);
        mvc = MockMvcBuilders.standaloneSetup(new ApplicationSchemaController(service))
                .setControllerAdvice(new GlobalExceptionHandler(), new ApplicationSchemaExceptionHandler())
                .build();
    }

    @Test
    void authorizedMemberGetsSchema() throws Exception {
        when(service.preview(10L)).thenReturn(new ApplicationSchema(
                1, "Peluqueria", "Peluqueria", "Peluqueria", List.of(
                        new ApplicationEntity("servicio", "Servicio", "Servicio", List.of(
                                new ApplicationField("servicio-id", "ID", "id", CanonicalType.INTEGER,
                                        true, false, true)))), List.of()));

        mvc.perform(get("/api/proyectos/10/generator/schema"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schemaVersion").value(1))
                .andExpect(jsonPath("$.projectName").value("Peluqueria"))
                .andExpect(jsonPath("$.entities[0].association").doesNotExist());
    }

    @Test
    void externalUserGetsForbidden() throws Exception {
        when(service.preview(10L)).thenThrow(new AccesoDenegadoException("Sin acceso"));

        mvc.perform(get("/api/proyectos/10/generator/schema"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.mensaje").value("Sin acceso"));
    }

    @Test
    void invalidPersistedDiagramProducesControlledConflict() throws Exception {
        when(service.preview(10L)).thenThrow(new ApplicationSchemaException("La entidad Corte no tiene una primary key."));

        mvc.perform(get("/api/proyectos/10/generator/schema"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.mensaje").value("La entidad Corte no tiene una primary key."));
    }
}
