package com.sw1.backend.collaboration.security;

import com.sw1.backend.auth.security.JwtService;
import com.sw1.backend.collaboration.config.WebSocketConfig;
import com.sw1.backend.collaboration.service.ProjectPresenceService;
import com.sw1.backend.common.exception.AccesoDenegadoException;
import com.sw1.backend.usuario.repository.UsuarioRepository;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;
import org.springframework.messaging.SubscribableChannel;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.broker.SimpleBrokerMessageHandler;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.context.support.TestPropertySourceUtils;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real Spring broker/channels/configuration; no network listener and no database. */
class CollaborationBrokerIntegrationTest {
    @Configuration
    static class Dependencies {
        @Bean JwtService jwtService() { return mock(JwtService.class); }
        @Bean UsuarioRepository users() { return mock(UsuarioRepository.class); }
        @Bean ProjectCollaborationAuthorizer authorizer() { return mock(ProjectCollaborationAuthorizer.class); }
    }

    @Test
    void configuredBrokerDeliversOnlyToActiveAuthorizedProjectSubscriptions() throws Exception {
        try (var context = new AnnotationConfigWebApplicationContext()) {
            context.setServletContext(new MockServletContext());
            TestPropertySourceUtils.addInlinedPropertiesToEnvironment(context, "cors.allowed-origin=http://localhost:5173");
            context.register(Dependencies.class, WebSocketConfig.class, StompJwtChannelInterceptor.class,
                    ProjectSubscriptionInterceptor.class, ProjectPresenceService.class);
            context.refresh();
            var messages = new LinkedBlockingQueue<Message<?>>();
            context.getBean("clientOutboundChannel", SubscribableChannel.class).subscribe(message -> {
                if (SimpMessageHeaderAccessor.getMessageType(message.getHeaders()) == SimpMessageType.MESSAGE)
                    messages.add(message);
            });
            var presence = context.getBean(ProjectPresenceService.class);
            var identity = new CollaborationIdentity(7L, "ana@example.com", "Ana");
            var broker = context.getBean(SimpleBrokerMessageHandler.class);
            var connect = SimpMessageHeaderAccessor.create(SimpMessageType.CONNECT);
            connect.setSessionId("session-A");
            connect.setUser(() -> identity.email());
            broker.handleMessage(MessageBuilder.createMessage(new byte[0], connect.getMessageHeaders()));
            // Register presence before subscribing so the join event cannot obscure this assertion.
            presence.join("session-A", "sub-A", 10L, identity);
            var headers = SimpMessageHeaderAccessor.create(SimpMessageType.SUBSCRIBE);
            headers.setSessionId("session-A"); headers.setSubscriptionId("sub-A");
            headers.setDestination("/topic/proyectos/10");
            broker.handleMessage(
                    MessageBuilder.createMessage(new byte[0], headers.getMessageHeaders()));

            var messaging = context.getBean(SimpMessagingTemplate.class);
            messaging.convertAndSend("/topic/proyectos/10", "confirmed");
            var delivered = messages.poll(5, TimeUnit.SECONDS);
            assertNotNull(delivered);
            assertEquals("confirmed", new String((byte[]) delivered.getPayload(), StandardCharsets.UTF_8));
            assertEquals("sub-A", SimpMessageHeaderAccessor.getSubscriptionId(delivered.getHeaders()));

            var authorizer = context.getBean(ProjectCollaborationAuthorizer.class);
            verify(authorizer).authorize(eq(10L), argThat(principal -> "ana@example.com".equals(principal.getName())));
            doThrow(new AccesoDenegadoException("Revoked")).when(authorizer).authorize(eq(10L), any());
            messaging.convertAndSend("/topic/proyectos/10", "must-not-arrive");
            messaging.convertAndSend("/topic/proyectos/11", "other-project");
            assertNull(messages.poll(250, TimeUnit.MILLISECONDS));
        }
    }
}
