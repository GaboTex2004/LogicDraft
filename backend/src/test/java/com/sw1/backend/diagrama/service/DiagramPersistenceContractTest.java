package com.sw1.backend.diagrama.service;

import com.sw1.backend.diagrama.model.Diagrama;
import jakarta.persistence.Version;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiagramPersistenceContractTest {
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
