package com.sw1.backend.ai.image;

import com.sun.net.httpserver.HttpServer;
import com.sw1.backend.ai.client.AiServiceException;
import com.sw1.backend.ai.service.ImageInterpretationService;
import com.sw1.backend.diagrama.validation.DiagramValidationException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import tools.jackson.databind.ObjectMapper;
import static com.sw1.backend.ai.image.ImageDiagramReconcilerTest.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ImageInterpretationServiceTest {
    private HttpServer server;
    private ImageInterpretationService service;
    private final AtomicReference<String> sent = new AtomicReference<>();
    private final AtomicReference<String> response = new AtomicReference<>();
    private final AtomicInteger statusCode = new AtomicInteger(200);
    private final ImageProposalService proposals = mock(ImageProposalService.class);
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach void setup() throws Exception {
        var document = base(); firstData(document).put("externalMetadata", m("source", "enterprise-architect", "externalId", "EAID_TEST"));
        var snapshot = new ImageProposalService.Snapshot(10L, 7L, 4L, document);
        when(proposals.load(10L)).thenReturn(snapshot);
        when(proposals.prepare(eq(snapshot), anyMap())).thenAnswer(call -> {
            Map<String, Object> raw = call.getArgument(1);
            var result = ImageDiagramReconciler.reconcile(document, raw);
            return new ImageProposalService.Proposal("test", 4L, result.matched(), result.additions(), result.conflicts(), result.warnings(), result.canApply());
        });
        statusCode.set(200);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/ai/image/interpret", exchange -> {
            sent.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = response.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(statusCode.get(), body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        service = new ImageInterpretationService(proposals, json, "http://127.0.0.1:" + server.getAddress().getPort());
    }
    @AfterEach void stop() { server.stop(0); }
    private MockMultipartFile image() { return new MockMultipartFile("image", "uml.png", "image/png", new byte[]{1, 2, 3}); }
    @Test void sendsCompleteUmlSnapshotAndReturnsNonmutatingProposal() {
        response.set(json.writeValueAsString(recognition(classifier("Cliente", "CLASS", java.util.List.of(m("name", "extra", "type", "String"))))));
        var result = service.interpret(10L, image(), "Reconoce las altas");
        assertTrue(result.canApply()); assertEquals(4L, result.baseRevision());
        assertTrue(sent.get().contains("EAID_TEST")); assertTrue(sent.get().contains("\"kind\":\"CLASS\""));
        assertTrue(sent.get().contains("\"nodes\"")); assertTrue(sent.get().contains("\"methods\""));
        verify(proposals, never()).accept(anyLong(), anyString(), anyString());
    }
    @Test void acceptsValidLargeImageAtMaxLimitAndRejectsOversizedImage() {
        response.set(json.writeValueAsString(recognition(classifier("Cliente", "CLASS", java.util.List.of(m("name", "extra", "type", "String"))))));
        var maxImage = new MockMultipartFile("image", "large.png", "image/png", new byte[5 * 1024 * 1024]);
        var okResult = service.interpret(10L, maxImage, "");
        assertTrue(okResult.canApply());

        var oversized = new MockMultipartFile("image", "huge.png", "image/png", new byte[5 * 1024 * 1024 + 1]);
        var ex = assertThrows(AiServiceException.class, () -> service.interpret(10L, oversized, ""));
        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, ex.getStatus());
        assertTrue(ex.getMessage().contains("5 MB"));
    }
    @Test void propagatesSpecificErrorDetailsFromFastApiFor502And503And504() {
        statusCode.set(502);
        response.set("{\"detail\":\"Gemini devolvió una respuesta que no cumple el contrato image-v1.\"}");
        var ex502 = assertThrows(AiServiceException.class, () -> service.interpret(10L, image(), ""));
        assertEquals(HttpStatus.BAD_GATEWAY, ex502.getStatus());
        assertEquals("Gemini devolvió una respuesta que no cumple el contrato image-v1.", ex502.getMessage());

        statusCode.set(503);
        response.set("{\"detail\":\"Gemini alcanzó su límite de solicitudes o cuota. Inténtalo más tarde.\"}");
        var ex503 = assertThrows(AiServiceException.class, () -> service.interpret(10L, image(), ""));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, ex503.getStatus());
        assertEquals("Gemini alcanzó su límite de solicitudes o cuota. Inténtalo más tarde.", ex503.getMessage());

        statusCode.set(504);
        response.set("{\"detail\":\"Se agotó el tiempo de conexión con Gemini.\"}");
        var ex504 = assertThrows(AiServiceException.class, () -> service.interpret(10L, image(), ""));
        assertEquals(HttpStatus.GATEWAY_TIMEOUT, ex504.getStatus());
        assertEquals("Se agotó el tiempo de conexión con Gemini.", ex504.getMessage());
    }
    @Test void rejectsDestructiveFastApiResponseAtSpringBoundary() {
        response.set("{\"operations\":[{\"type\":\"DELETE_ENTITY\",\"entityName\":\"Cliente\"}]}");
        assertThrows(DiagramValidationException.class, () -> service.interpret(10L, image(), ""));
    }
    @Test void rejectsModifyingFastApiResponseAtSpringBoundary() {
        response.set("{\"operations\":[{\"type\":\"UPDATE_ATTRIBUTE\",\"entityName\":\"Cliente\"}]}");
        assertThrows(DiagramValidationException.class, () -> service.interpret(10L, image(), ""));
    }
}
