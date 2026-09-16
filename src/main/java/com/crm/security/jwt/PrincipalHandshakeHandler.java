package com.crm.security.jwt;

import org.springframework.http.server.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.support.DefaultHandshakeHandler;

import java.security.Principal;
import java.util.Map;

/**
 * {@link JwtHandshakeInterceptor} qo'ygan autentifikatsiyani STOMP
 * sessiyasining {@code Principal} iga aylantiradi.
 *
 * <p>Shundan keyin {@code @MessageMapping} metodlari {@code Principal}
 * argumentini oladi va {@code convertAndSendToUser} shaxsiy navbatga
 * ({@code /user/queue/…}) yo'naltira oladi.
 *
 * <p>{@code null} qaytishi mumkin emas: interceptor undan oldin ishlaydi
 * va tekshiruvdan o'tmagan so'rovni umuman shu yergacha qo'ymaydi.
 */
@Component
public class PrincipalHandshakeHandler extends DefaultHandshakeHandler {

    @Override
    protected Principal determineUser(ServerHttpRequest request,
                                       WebSocketHandler wsHandler,
                                       Map<String, Object> attributes) {
        Object authentication = attributes.get(JwtHandshakeInterceptor.AUTHENTICATION_ATTRIBUTE);
        return authentication instanceof Principal principal ? principal : null;
    }
}
