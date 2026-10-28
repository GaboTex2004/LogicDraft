package com.sw1.backend.ai.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sw1.backend.ai.client.AiServiceException;
import com.sw1.backend.ai.diagram.dto.DiagramContext;
import com.sw1.backend.ai.diagram.validation.DiagramOperationValidator;
import com.sw1.backend.ai.diagram.validation.SemanticOperationValidator;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.springframework.http.HttpStatus;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

class SemanticOperationValidatorTest {
    @TestFactory
    @SuppressWarnings("unchecked")
    Stream<DynamicTest> sharedPythonJavaContract() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        Map<String, Object> fixture;
        try (var stream = getClass().getResourceAsStream("/ai/semantic-safety-cases.json")) {
            assertNotNull(stream);
            fixture = mapper.readValue(stream, Map.class);
        }
        var context = mapper.convertValue(fixture.get("context"), DiagramContext.class);
        return ((List<Map<String, Object>>) fixture.get("cases")).stream().map(test ->
                DynamicTest.dynamicTest((String) test.get("name"), () -> {
                    var response = DiagramOperationValidator.validate(Map.of("operations", test.get("operations")));
                    var action = (org.junit.jupiter.api.function.Executable) () -> SemanticOperationValidator.validate(
                            (String) test.get("prompt"), context, response.operations());
                    if (Boolean.TRUE.equals(test.get("allowed"))) assertDoesNotThrow(action);
                    else {
                        var rejected = assertThrows(AiServiceException.class, action);
                        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, rejected.getStatus());
                        assertEquals("SEMANTIC_TARGET_MISMATCH", rejected.getTechnicalDetail());
                    }
                }));
    }
}
