package com.sw1.backend.ai.client;

import com.sun.net.httpserver.HttpServer;
import com.sw1.backend.ai.dto.request.AiGenerateRequest;
import com.sw1.backend.ai.agent.dto.AgentProjectContext;
import com.sw1.backend.ai.agent.dto.AgentUpstreamRequest;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import static org.junit.jupiter.api.Assertions.*;

class AiServiceClientTest {
    private HttpServer server;
    private AiServiceClient client;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        client = new AiServiceClient("http://127.0.0.1:" + server.getAddress().getPort(), 1);
        server.start();
    }

    @AfterEach
    void stop() { server.stop(0); }

    void respond(int status, String body) {
        server.createContext("/api/ai/generate", exchange -> {
            assertEquals("POST", exchange.getRequestMethod());
            String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(request.contains("prompt"));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
    }

    void respondAgent(int status, String body) {
        server.createContext("/api/agent/ask", exchange -> {
            assertEquals("POST", exchange.getRequestMethod());
            String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(request.contains("\"context\""));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
    }

    @Test void returnsContent() {
        respond(200, "{\"content\":\"respuesta\"}");
        assertEquals("respuesta", client.generate(new AiGenerateRequest("hola")).content());
    }

    @Test void returnsTextualAgentAnswer() {
        respondAgent(200, "{\"answer\":\"Producto tiene una relacion.\"}");
        var context = new AgentProjectContext(10L, "Tienda", null, null, null,
                java.util.List.of(), java.util.List.of(), java.util.List.of());
        var response = client.askAgent(new AgentUpstreamRequest("revisa", context));
        assertEquals("Producto tiene una relacion.", response.answer());
        assertTrue(response.operations().isEmpty());
    }

    @Test void returnsValidatedAgentManyToManyOperations() {
        respondAgent(200, """
                {"answer":"Relacion propuesta.","operations":[{"type":"ADD_RELATIONSHIP","relationship":{
                "sourceEntity":"Alumno","targetEntity":"Materia","sourceCardinality":"ZERO_MANY",
                "targetCardinality":"ONE_MANY","name":"materias","joinTableName":"alumno_materia"}}]}
                """);
        var context = new AgentProjectContext(10L, "Academia", null, null, null,
                java.util.List.of(), java.util.List.of(), java.util.List.of());
        var response = client.askAgent(new AgentUpstreamRequest("relaciona", context));
        assertEquals(1, response.operations().size());
        assertEquals("materias", response.operations().getFirst().relationship().name());
        assertEquals("alumno_materia", response.operations().getFirst().relationship().joinTableName());
    }

    @Test void rejectsInvalidAgentAnswer() {
        respondAgent(200, "{\"answer\":123}");
        var context = new AgentProjectContext(10L, "Tienda", null, null, null,
                java.util.List.of(), java.util.List.of(), java.util.List.of());
        var error = assertThrows(AiServiceException.class,
                () -> client.askAgent(new AgentUpstreamRequest("revisa", context)));
        assertEquals(HttpStatus.BAD_GATEWAY, error.getStatus());
    }

    @Test void rejectsMalformedJson() { respond(200, "not-json"); fails(HttpStatus.BAD_GATEWAY); }
    @Test void rejectsWrongContentType() { respond(200, "{\"content\":123}"); fails(HttpStatus.BAD_GATEWAY); }
    @Test void rejectsMissingContent() { respond(200, "{}"); fails(HttpStatus.BAD_GATEWAY); }
    @Test void mapsIncompleteBatchWithoutLeakingUpstreamBody() {
        respond(422, "{\"detail\":\"private\"}");
        fails(HttpStatus.UNPROCESSABLE_ENTITY);
    }
    @Test void hidesUpstreamServerError() { respond(500, "{\"detail\":\"private\"}"); fails(HttpStatus.BAD_GATEWAY); }
    @Test void mapsUnavailable() { respond(503, "{}"); fails(HttpStatus.SERVICE_UNAVAILABLE); }
    @Test void mapsUpstreamTimeout() { respond(504, "{}"); fails(HttpStatus.GATEWAY_TIMEOUT); }
    @Test void handlesOfflineService() { server.stop(0); fails(HttpStatus.SERVICE_UNAVAILABLE); }
    @Test void handlesReadTimeout() {
        server.createContext("/api/ai/generate", exchange -> {
            try { Thread.sleep(1800); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        fails(HttpStatus.GATEWAY_TIMEOUT);
    }

    void fails(HttpStatus status) {
        var error = assertThrows(AiServiceException.class, () -> client.generate(new AiGenerateRequest("hola")));
        assertEquals(status, error.getStatus());
        assertFalse(error.getMessage().contains("private"));
    }
}
