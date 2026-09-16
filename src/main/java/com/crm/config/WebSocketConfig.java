package com.crm.config;

import com.crm.security.jwt.JwtHandshakeInterceptor;
import com.crm.security.jwt.PrincipalHandshakeHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * Ichki chatning STOMP ulanishi.
 *
 * <ul>
 *   <li>{@code /ws} — ulanish nuqtasi, tokeni {@code ?token=…} da;</li>
 *   <li>{@code /app} — mijozdan serverga ({@code @MessageMapping});</li>
 *   <li>{@code /topic} — suhbat lentasi, barcha ishtirokchilarga;</li>
 *   <li>{@code /queue} — shaxsiy ({@code /user/queue/errors}).</li>
 * </ul>
 *
 * <p>Broker — xotiradagi {@code SimpleBroker}. Bu ilova bitta nusxada
 * ishlayotgani uchun yetarli; bir nechta nusxa bo'lganda tashqi broker
 * (RabbitMQ) kerak bo'ladi, chunki obunalar nusxalar o'rtasida
 * taqsimlanmaydi.
 *
 * <p>Ruxsat etilgan manbalar {@link SecurityConfig} dagi ro'yxatdan
 * olinadi: CORS ikki joyda alohida yozilib, keyin bir-biridan ajralib
 * qolmasin.
 */
@Configuration
@EnableWebSocketMessageBroker
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final JwtHandshakeInterceptor jwtHandshakeInterceptor;
    private final PrincipalHandshakeHandler principalHandshakeHandler;
    private final ChatChannelInterceptor chatChannelInterceptor;

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
            .setAllowedOriginPatterns(
                SecurityConfig.ALLOWED_ORIGIN_PATTERNS.toArray(new String[0]))
            .addInterceptors(jwtHandshakeInterceptor)
            .setHandshakeHandler(principalHandshakeHandler);
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic", "/queue");
        registry.setApplicationDestinationPrefixes("/app");
        registry.setUserDestinationPrefix("/user");
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(chatChannelInterceptor);
    }
}
