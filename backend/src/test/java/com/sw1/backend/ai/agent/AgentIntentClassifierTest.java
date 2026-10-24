package com.sw1.backend.ai.agent;

import com.sw1.backend.ai.agent.service.AgentIntentClassifier;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AgentIntentClassifierTest {
    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            ¿Qué podría agregar a mi diagrama? | INFORMATIONAL
            ¿Cuáles entidades y relaciones? | INFORMATIONAL
            Explícame cómo se relacionan Alumno y Materia | INFORMATIONAL
            Podríamos agregar horarios | AMBIGUOUS
            Profesor | AMBIGUOUS
            Agrega una entidad Profesor | MODIFICATION
            Crea una relación entre Profesor y Materia | MODIFICATION
            Convierte la relación N:M en Inscripcion | MODIFICATION
            """)
    void classifiesAuthorizationFromUserWords(String message, String expected) {
        assertEquals(AgentIntentClassifier.Intent.valueOf(expected), AgentIntentClassifier.classify(message));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            Modifica el atributo nombre | MODIFICATION
            Renombra Cliente a Comprador | MODIFICATION
            Elimina la clase Producto | MODIFICATION
            Borra el atributo temporal | MODIFICATION
            Quita la relacion duplicada | MODIFICATION
            Suprime la entidad obsoleta | MODIFICATION
            Como elimino una clase? | INFORMATIONAL
            Que pasaria si elimino Producto? | INFORMATIONAL
            Sugiere mejoras al diagrama | INFORMATIONAL
            Analiza el diagrama | INFORMATIONAL
            No elimines Cliente | AMBIGUOUS
            Nunca borres Producto | AMBIGUOUS
            """)
    void classifiesEditsQuestionsAdviceAndNegations(String message, String expected) {
        assertEquals(AgentIntentClassifier.Intent.valueOf(expected), AgentIntentClassifier.classify(message));
    }

    @ParameterizedTest
    @CsvSource({"DELETE_ENTITY", "RENAME_ATTRIBUTE", "UPDATE_RELATIONSHIP", "DELETE_ASSOCIATION"})
    void detectsEveryTechnicalOperationInInformationalAnswers(String operation) {
        assertEquals(true, AgentIntentClassifier.containsTechnicalOperation("Respuesta " + operation));
    }
}
