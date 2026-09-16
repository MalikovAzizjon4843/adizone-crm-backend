package com.crm.security.jwt;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;

/**
 * WebSocket qo'l berishida (handshake) JWT ni tekshiradi.
 *
 * <p>Token so'rov parametrida keladi: {@code /ws?token=…}. Sabab —
 * brauzerning o'z {@code WebSocket} ob'ekti so'rovga sarlavha qo'sha
 * olmaydi, ya'ni {@code Authorization: Bearer} REST dagidek ishlamaydi.
 * Sarlavha ham o'qiladi: mobil va server-server mijozlar uni yubora oladi.
 *
 * <p>Token yo'q yoki yaroqsiz bo'lsa {@code false} qaytadi — ulanish
 * 403 bilan rad etiladi va STOMP sessiyasi umuman ochilmaydi. Ya'ni
 * autentifikatsiyasiz mijoz hech qanday topikka obuna bo'la olmaydi.
 *
 * <p>Tekshiruvdan o'tgan foydalanuvchi {@code attributes} ga qo'yiladi,
 * u yerdan {@link PrincipalHandshakeHandler} sessiya {@code Principal} iga
 * aylantiradi.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class JwtHandshakeInterceptor implements HandshakeInterceptor {

    /** {@code attributes} dagi kalit — handshake bilan handler o'rtasidagi kelishuv. */
    public static final String AUTHENTICATION_ATTRIBUTE = "chat.authentication";

    private static final String TOKEN_PARAM = "token";
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtUtils jwtUtils;
    private final UserDetailsService userDetailsService;

    @Override
    public boolean beforeHandshake(ServerHttpRequest request,
                                    ServerHttpResponse response,
                                    WebSocketHandler wsHandler,
                                    Map<String, Object> attributes) {
        String token = extractToken(request);
        if (token == null) {
            return reject(response, "token yo'q");
        }

        try {
            String username = jwtUtils.extractUsername(token);
            if (username == null) {
                return reject(response, "tokenda foydalanuvchi yo'q");
            }
            // loadUserByUsername nofaol hisobda istisno tashlaydi —
            // o'chirilgan xodimning eski tokeni bilan ulanib bo'lmaydi.
            UserDetails userDetails = userDetailsService.loadUserByUsername(username);
            if (!jwtUtils.isTokenValid(token, userDetails)) {
                return reject(response, "token yaroqsiz yoki muddati o'tgan");
            }

            Authentication authentication = new UsernamePasswordAuthenticationToken(
                userDetails, null, userDetails.getAuthorities());
            attributes.put(AUTHENTICATION_ATTRIBUTE, authentication);
            return true;
        } catch (Exception e) {
            return reject(response, e.getMessage());
        }
    }

    @Override
    public void afterHandshake(ServerHttpRequest request,
                                ServerHttpResponse response,
                                WebSocketHandler wsHandler,
                                Exception exception) {
        // Kerak emas.
    }

    private String extractToken(ServerHttpRequest request) {
        if (request instanceof ServletServerHttpRequest servletRequest) {
            String param = servletRequest.getServletRequest().getParameter(TOKEN_PARAM);
            if (param != null && !param.isBlank()) {
                return param.trim();
            }
        }
        String header = request.getHeaders().getFirst("Authorization");
        if (header != null && header.startsWith(BEARER_PREFIX)) {
            return header.substring(BEARER_PREFIX.length()).trim();
        }
        return null;
    }

    private boolean reject(ServerHttpResponse response, String reason) {
        log.debug("WebSocket handshake rad etildi: {}", reason);
        response.setStatusCode(HttpStatus.FORBIDDEN);
        return false;
    }
}
