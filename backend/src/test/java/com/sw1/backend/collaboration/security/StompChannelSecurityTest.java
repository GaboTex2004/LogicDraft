package com.sw1.backend.collaboration.security;

import com.sw1.backend.auth.security.JwtService;
import com.sw1.backend.collaboration.service.ProjectPresenceService;
import com.sw1.backend.common.exception.AccesoDenegadoException;
import com.sw1.backend.usuario.repository.UsuarioRepository;
import java.security.Principal;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StompChannelSecurityTest {
    @Test
    void rejectsUnauthenticatedFramesAndDirectBrokerPublications() {
        var authorizer = mock(ProjectCollaborationAuthorizer.class);
        var interceptor = new StompJwtChannelInterceptor(mock(JwtService.class), mock(UsuarioRepository.class), authorizer);
        assertThrows(MessageDeliveryException.class, () -> interceptor.preSend(inbound(StompCommand.CONNECT, null, null), null));
        assertThrows(MessageDeliveryException.class, () -> interceptor.preSend(inbound(StompCommand.SUBSCRIBE, "/topic/proyectos/10", null), null));
        assertThrows(MessageDeliveryException.class, () -> interceptor.preSend(inbound(StompCommand.SEND, "/topic/proyectos/10", () -> "ana"), null));
        verifyNoInteractions(authorizer);
    }

    @Test
    void subscriptionAndPublicationResolveAccessFromAuthenticatedPrincipalAndDestination() {
        var authorizer = mock(ProjectCollaborationAuthorizer.class);
        var interceptor = new StompJwtChannelInterceptor(mock(JwtService.class), mock(UsuarioRepository.class), authorizer);
        Principal principal = () -> "ana@example.com";
        var allowed = inbound(StompCommand.SUBSCRIBE, "/topic/proyectos/10", principal);
        assertSame(allowed, interceptor.preSend(allowed, null));
        verify(authorizer).authorize(10L, principal);
        doThrow(new AccesoDenegadoException("Otro workspace")).when(authorizer).authorize(11L, principal);
        assertThrows(MessageDeliveryException.class, () -> interceptor.preSend(inbound(StompCommand.SUBSCRIBE, "/topic/proyectos/11", principal), null));
        assertThrows(MessageDeliveryException.class, () -> interceptor.preSend(inbound(StompCommand.SEND, "/app/proyectos/11/eventos", principal), null));
    }

    @Test
    void removedMemberCannotReceiveMessagesThroughAnExistingSubscription() {
        var presence = mock(ProjectPresenceService.class);
        var authorizer = mock(ProjectCollaborationAuthorizer.class);
        var identity = new CollaborationIdentity(7L, "ana@example.com", "Ana");
        when(presence.subscriber("session-A", "sub-A", 10L)).thenReturn(identity);
        var interceptor = new ProjectSubscriptionInterceptor(
                new org.springframework.beans.factory.ObjectProvider<ProjectPresenceService>() {
                    @Override public ProjectPresenceService getObject() { return presence; }
                }, new org.springframework.beans.factory.ObjectProvider<ProjectCollaborationAuthorizer>() {
                    @Override public ProjectCollaborationAuthorizer getObject() { return authorizer; }
                });
        Message<?> message = outbound(10L);
        assertSame(message, interceptor.preSend(message, null));
        verify(authorizer).authorize(eq(10L), argThat(principal -> principal.getName().equals("ana@example.com")));
        doThrow(new AccesoDenegadoException("Revocado")).when(authorizer).authorize(eq(10L), any());
        assertNull(interceptor.preSend(message, null));
        assertNull(interceptor.preSend(outbound(11L), null));
    }

    @Test
    void unsubscribeRemovesOnlyThatSubscriptionAndNotOtherTabs() {
        var presence = new ProjectPresenceService(mock(org.springframework.messaging.simp.SimpMessagingTemplate.class));
        var identity = new CollaborationIdentity(7L, "ana@example.com", "Ana");
        presence.join("session-A", "sub-A", 10L, identity);
        presence.join("session-B", "sub-B", 10L, identity);
        presence.leaveSubscription("session-A", "sub-A");
        assertNull(presence.subscriber("session-A", "sub-A", 10L));
        assertEquals(identity, presence.subscriber("session-B", "sub-B", 10L));
        assertNull(presence.subscriber("session-B", "sub-B", 11L));
        presence.leaveSession("session-B");
        assertNull(presence.subscriber("session-B", "sub-B", 10L));
    }

    private static Message<?> inbound(StompCommand command, String destination, Principal principal) {
        var headers = StompHeaderAccessor.create(command);
        headers.setDestination(destination);
        headers.setUser(principal);
        headers.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], headers.getMessageHeaders());
    }

    private static Message<?> outbound(Long projectId) {
        var headers = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        headers.setDestination("/topic/proyectos/" + projectId);
        headers.setSessionId("session-A");
        headers.setSubscriptionId("sub-A");
        return MessageBuilder.createMessage(new byte[0], headers.getMessageHeaders());
    }
}
