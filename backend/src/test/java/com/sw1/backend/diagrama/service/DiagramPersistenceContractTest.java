package com.sw1.backend.diagrama.service;

import com.sw1.backend.diagrama.model.Diagrama;
import jakarta.persistence.Version;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

class DiagramPersistenceContractTest {
    @Test
    void saveMetadataIsOptionalForLegacyClientsButRejectsNegativeRevisionAndInvalidClientId() {
        var request = new com.sw1.backend.diagrama.dto.request.GuardarDiagramaRequest();
        request.setVersion(1);
        request.setNodes(java.util.List.of());
        request.setEdges(java.util.List.of());
        try (var factory = jakarta.validation.Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            assertTrue(validator.validate(request).isEmpty());
            request.setBaseRevision(-1L);
            assertFalse(validator.validate(request).isEmpty());
            request.setBaseRevision(0L);
            request.setClientId("browser-A_1");
            assertTrue(validator.validate(request).isEmpty());
            request.setClientId("foreign/client");
            assertFalse(validator.validate(request).isEmpty());
        }
    }

    @Test
    void concurrencyRevisionIsJpaVersionAndIndependentFromFormatVersion() throws Exception {
        assertNotNull(Diagrama.class.getDeclaredField("revision").getAnnotation(Version.class));
        assertTrue(Diagrama.class.getDeclaredField("version").getAnnotation(Version.class) == null);
    }

    @Test
    void flywayAddsRevisionWithoutDeletingOrRewritingData() throws Exception {
        try (var stream = getClass().getResourceAsStream("/db/migration/V2__diagram_revision.sql")) {
            assertNotNull(stream);
            String sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8).toLowerCase();
            assertTrue(sql.contains("add column if not exists revision bigint not null default 0"));
            assertTrue(!sql.contains("drop "));
            assertTrue(!sql.contains("truncate "));
            assertTrue(!sql.contains("delete "));
        }
    }
}
