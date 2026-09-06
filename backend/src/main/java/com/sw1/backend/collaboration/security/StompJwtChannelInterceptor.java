package com.sw1.backend.collaboration.security;

import com.sw1.backend.auth.security.JwtService;
import com.sw1.backend.usuario.model.Usuario;
import com.sw1.backend.usuario.repository.UsuarioRepository;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;

import java.security.Principal;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class StompJwtChannelInterceptor implements ChannelInterceptor {
    private static final Pattern SUBSCRIPTION_DESTINATION = Pattern.compile("^/topic/proyectos/(\\d+)$");
    private static final Pattern PUBLICATION_DESTINATION = Pattern.compile("^/app/proyectos/(\\d+)/eventos$");
    private final JwtService jwtService;
    private final UsuarioRepository usuarioRepository;
    private final ProjectCollaborationAuthorizer authorizer;

    public StompJwtChannelInterceptor(
            JwtService jwtService,
            UsuarioRepository usuarioRepository,
            ProjectCollaborationAuthorizer authorizer) {
        this.jwtService = jwtService;
        this.usuarioRepository = usuarioRepository;
        this.authorizer = authorizer;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || accessor.getCommand() == null) return message;
        if (StompCommand.CONNECT.equals(accessor.getCommand())) {
            accessor.setUser(authenticate(accessor));
        } else if (StompCommand.SUBSCRIBE.equals(accessor.getCommand())) {
            authorizeDestination(accessor, SUBSCRIPTION_DESTINATION);
        } else if (StompCommand.SEND.equals(accessor.getCommand())) {
            authorizeDestination(accessor, PUBLICATION_DESTINATION);
        }
        return message;
    }

    private Principal authenticate(StompHeaderAccessor accessor) {
        String authorization = accessor.getFirstNativeHeader("Authorization");
        if (authorization == null) authorization = accessor.getFirstNativeHeader("authorization");
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw new MessageDeliveryException("Se requiere JWT en el frame STOMP CONNECT");
        }
        try {
            String token = authorization.substring(7);
            String email = jwtService.extraerEmail(token);
            if (!jwtService.validarToken(token, email)) {
                throw new MessageDeliveryException("JWT inválido o expirado");
            }
            Usuario usuario = usuarioRepository.findByEmail(email)
                    .orElseThrow(() -> new MessageDeliveryException("El usuario autenticado ya no existe"));
            return new UsernamePasswordAuthenticationToken(
                    email, null,
                    List.of(new SimpleGrantedAuthority("ROLE_" + usuario.getRolSistema().name())));
        } catch (MessageDeliveryException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new MessageDeliveryException("JWT inválido o expirado");
        }
    }

    private void authorizeDestination(StompHeaderAccessor accessor, Pattern allowedPattern) {
        Principal principal = accessor.getUser();
        if (principal == null) throw new MessageDeliveryException("La sesión STOMP no está autenticada");
        String destination = accessor.getDestination();
        Matcher matcher = destination == null ? allowedPattern.matcher("") : allowedPattern.matcher(destination);
        if (!matcher.matches()) throw new MessageDeliveryException("Destino STOMP no permitido");
        try {
            authorizer.authorize(Long.valueOf(matcher.group(1)), principal);
        } catch (RuntimeException exception) {
            throw new MessageDeliveryException("No tienes acceso al proyecto solicitado");
        }
    }
}
