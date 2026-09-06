package com.sw1.backend.ai.client;

import com.sw1.backend.ai.dto.request.AiGenerateRequest;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AiRequestValidationTest {
    @Test void validatesPromptBounds() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            for (String prompt : new String[] {null, "", "   ", "x".repeat(10001)}) {
                assertFalse(validator.validate(new AiGenerateRequest(prompt)).isEmpty());
            }
            assertTrue(validator.validate(new AiGenerateRequest("hola")).isEmpty());
        }
    }
}
