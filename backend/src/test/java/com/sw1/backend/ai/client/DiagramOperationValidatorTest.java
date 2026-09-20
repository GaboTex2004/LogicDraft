package com.sw1.backend.ai.client;

import com.sw1.backend.ai.diagram.validation.DiagramOperationValidator;
import com.sw1.backend.ai.diagram.model.DiagramOperationType;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DiagramOperationValidatorTest {
    Map<String, Object> attribute = Map.of("name", "id", "dataType", "Long", "primaryKey", true, "nullable", false);

    @Test void acceptsThreeOperations() {
        var result = DiagramOperationValidator.validate(Map.of("operations", List.of(
            Map.of("type", "ADD_ENTITY", "entity", Map.of("name", "Cliente", "attributes", List.of(attribute))),
            Map.of("type", "ADD_ATTRIBUTE", "entityName", "Cliente", "attribute", attribute),
            Map.of("type", "ADD_RELATIONSHIP", "relationship", Map.of("sourceEntity", "Cliente", "targetEntity", "Pedido", "sourceCardinality", "ONE_ONE", "targetCardinality", "ZERO_MANY"))
        )));
        assertEquals(3, result.operations().size());
        assertEquals(DiagramOperationType.ADD_RELATIONSHIP, result.operations().get(2).type());
    }

    @Test void rejectsUntrustedResponses() {
        for (var operation : List.of(
            Map.of("type", "DELETE_ENTITY"),
            Map.of("type", "ADD_ENTITY", "entity", Map.of("name", "", "attributes", List.of())),
            Map.of("type", "ADD_ATTRIBUTE", "entityName", "Cliente", "attribute", Map.of("name", "x", "dataType", "SQL", "primaryKey", false, "nullable", true)),
            Map.of("type", "ADD_RELATIONSHIP", "relationship", Map.of("sourceEntity", "A", "targetEntity", "B", "relationshipType", "INVALID"))
        )) {
            assertThrows(AiServiceException.class, () -> DiagramOperationValidator.validate(Map.of("operations", List.of(operation))));
        }
        assertThrows(AiServiceException.class, () -> DiagramOperationValidator.validate(null));
        assertThrows(AiServiceException.class, () -> DiagramOperationValidator.validate(Map.of("operations", List.of(), "sql", "private")));
    }

    @Test void acceptsStrictManyToManyAssociationConversion() {
        var own = Map.of("name", "nota", "dataType", "Integer", "primaryKey", false, "nullable", true);
        var result = DiagramOperationValidator.validate(Map.of("operations", List.of(Map.of(
                "type", "CONVERT_MANY_TO_MANY_ASSOCIATION",
                "conversion", Map.of("relationshipId", "student-subject", "sourceEntity", "Alumno",
                        "targetEntity", "Materia", "associationEntityName", "Inscripcion",
                        "attributes", List.of(own))))));
        assertEquals(DiagramOperationType.CONVERT_MANY_TO_MANY_ASSOCIATION, result.operations().getFirst().type());
        assertEquals("student-subject", result.operations().getFirst().conversion().relationshipId());
        assertEquals("nota", result.operations().getFirst().conversion().attributes().getFirst().name());

        var generatedPk = new LinkedHashMap<>(own);
        generatedPk.put("primaryKey", true);
        assertThrows(AiServiceException.class, () -> DiagramOperationValidator.validate(Map.of("operations", List.of(Map.of(
                "type", "CONVERT_MANY_TO_MANY_ASSOCIATION",
                "conversion", Map.of("relationshipId", "student-subject", "sourceEntity", "Alumno",
                        "targetEntity", "Materia", "associationEntityName", "Inscripcion",
                        "attributes", List.of(generatedPk)))))));
    }
}
