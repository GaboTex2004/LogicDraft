package com.sw1.backend.auth.security;

import com.sw1.backend.usuario.model.RolSistema;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

@Service
public class JwtService {

    private final SecretKey clave;
    private final long expiracionMilisegundos;

    public JwtService(
            @Value("${jwt.secret}") String secreto,
            @Value("${jwt.expiration-ms}") long expiracionMilisegundos) {
        this.clave = Keys.hmacShaKeyFor(secreto.getBytes(StandardCharsets.UTF_8));
        this.expiracionMilisegundos = expiracionMilisegundos;
    }

    public String generarToken(String email, RolSistema rolSistema) {
        Date ahora = new Date();
        Date expiracion = new Date(ahora.getTime() + expiracionMilisegundos);

        return Jwts.builder()
                .subject(email)
                .claim("rolSistema", rolSistema.name())
                .issuedAt(ahora)
                .expiration(expiracion)
                .signWith(clave)
                .compact();
    }

    public String extraerEmail(String token) {
        return extraerClaims(token).getSubject();
    }

    public RolSistema extraerRolSistema(String token) {
        String rol = extraerClaims(token).get("rolSistema", String.class);
        return RolSistema.valueOf(rol);
    }

    public boolean validarToken(String token, String email) {
        try {
            Claims claims = extraerClaims(token);
            return email.equals(claims.getSubject())
                    && claims.getExpiration().after(new Date());
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private Claims extraerClaims(String token) {
        return Jwts.parser()
                .verifyWith(clave)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
