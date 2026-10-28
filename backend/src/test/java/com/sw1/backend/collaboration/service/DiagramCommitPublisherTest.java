package com.sw1.backend.collaboration.service;

import com.sw1.backend.auth.service.UsuarioActualService;
import com.sw1.backend.collaboration.dto.CollaborationEvent;
import com.sw1.backend.diagrama.dto.response.DiagramaResponse;
import com.sw1.backend.usuario.model.Usuario;
import java.time.LocalDateTime;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.TransactionDefinition;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DiagramCommitPublisherTest {
    @Configuration
    @EnableTransactionManagement
    static class Config {}

    @Test
    void publishesOnlyAfterCommitNeverOnRollbackAndUsesAuthenticatedIdentity() {
        var messaging = mock(SimpMessagingTemplate.class);
        var users = mock(UsuarioActualService.class);
        var user = new Usuario();
        user.setId(7L);
        user.setNombre("Ana");
        when(users.obtenerUsuarioActual()).thenReturn(user);
        try (var context = new AnnotationConfigApplicationContext()) {
            context.register(Config.class);
            context.registerBean(UsuarioActualService.class, () -> users);
            context.registerBean(SimpMessagingTemplate.class, () -> messaging);
            context.registerBean(DiagramCommitPublisher.class);
            context.refresh();
            var publisher = context.getBean(DiagramCommitPublisher.class);
            var transaction = new TransactionTemplate(new MemoryTransactions());
            var saved = new DiagramaResponse(30L, 10L, 2, 5L, Map.of(), LocalDateTime.now());

            transaction.executeWithoutResult(status -> {
                publisher.schedule(saved, "browser-A");
                verifyNoInteractions(messaging);
                status.setRollbackOnly();
            });
            verifyNoInteractions(messaging);
            transaction.executeWithoutResult(status -> {
                publisher.schedule(saved, "browser-A");
                verifyNoInteractions(messaging);
            });
            var event = ArgumentCaptor.forClass(CollaborationEvent.class);
            verify(messaging).convertAndSend(eq("/topic/proyectos/10"), event.capture());
            assertEquals("diagram:30:5", event.getValue().eventId());
            assertEquals(7L, event.getValue().userId());
            assertEquals("Ana", event.getValue().name());
            assertEquals("browser-A", event.getValue().clientId());
            assertEquals(Map.of("diagramId", 30L, "revision", 5L), event.getValue().payload());
            assertFalse(event.getValue().payload().containsKey("document"));

            doThrow(new IllegalStateException("broker offline")).when(messaging)
                    .convertAndSend(eq("/topic/proyectos/10"), any(CollaborationEvent.class));
            assertDoesNotThrow(() -> transaction.executeWithoutResult(status -> publisher.schedule(saved, null)));
        }
    }

    /** Spring transaction synchronizations, without a connection or a database. */
    private static class MemoryTransactions extends AbstractPlatformTransactionManager {
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object transaction, TransactionDefinition definition) {}
        @Override protected void doCommit(DefaultTransactionStatus status) {}
        @Override protected void doRollback(DefaultTransactionStatus status) {}
    }
}
