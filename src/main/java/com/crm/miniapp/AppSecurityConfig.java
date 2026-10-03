package com.crm.miniapp;

import com.crm.config.Messages;
import com.crm.exception.ErrorResponse;
import com.crm.repository.AppIdentityRepository;
import com.crm.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfigurationSource;

import java.io.IOException;
import java.time.LocalDateTime;

/**
 * Mini App xavfsizlik zanjiri (docs/design/telegram-platform.md §3.3): {@code /api/app/**} faqat shu
 * zanjirda, {@code @Order(1)} — admin zanjiridan oldin. Zanjir faqat app kalitini biladi: admin
 * tokeni bu yerda 401, app tokeni esa admin zanjirida (boshqa kalit) 401.
 *
 * <ul>
 *   <li>{@code POST /api/app/auth} — ochiq, initData HMAC bilan himoyalangan;</li>
 *   <li>qolgani — {@code ROLE_APP} ({@link AppJwtFilter}).</li>
 * </ul>
 */
@Configuration
public class AppSecurityConfig {

    public static final String PATH = "/api/app/**";

    @Bean
    @Order(1)
    public SecurityFilterChain appSecurityFilterChain(HttpSecurity http,
                                                      CorsConfigurationSource corsConfigurationSource,
                                                      AppJwtService jwtService,
                                                      AppIdentityRepository identityRepository,
                                                      UserRepository userRepository,
                                                      ObjectMapper objectMapper,
                                                      Messages messages) throws Exception {
        http
            .securityMatcher(PATH)
            .csrf(AbstractHttpConfigurer::disable)
            .cors(cors -> cors.configurationSource(corsConfigurationSource))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                // initData (HMAC) bilan himoyalangan — token hali yo'q
                .requestMatchers(HttpMethod.POST, "/api/app/auth", "/api/app/link/manual").permitAll()
                // O'qituvchi rejimi (telegram-platform §11.4) — rol bazadan, AppJwtFilter
                .requestMatchers("/api/app/teacher/**").hasAuthority(AppJwtFilter.ROLE_TEACHER)
                .anyRequest().hasAuthority(AppJwtFilter.ROLE))
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint((req, res, e) ->
                    write(res, HttpStatus.UNAUTHORIZED, "app.auth.unauthorized", objectMapper, messages))
                .accessDeniedHandler((req, res, e) ->
                    write(res, HttpStatus.FORBIDDEN, "app.forbidden", objectMapper, messages)))
            .addFilterBefore(new AppJwtFilter(jwtService, identityRepository, userRepository),
                UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    private static void write(HttpServletResponse response, HttpStatus status, String code,
                              ObjectMapper objectMapper, Messages messages) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getOutputStream(), ErrorResponse.builder()
            .timestamp(LocalDateTime.now())
            .status(status.value())
            .error(status.getReasonPhrase())
            .message(messages.get(code))
            .code(code)
            .build());
    }
}
