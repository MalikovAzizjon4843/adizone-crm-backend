package com.crm.calls;

import com.crm.config.Messages;
import com.crm.exception.ErrorResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
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

import java.io.IOException;
import java.time.LocalDateTime;

/**
 * "Adizone Qo'ng'iroqlar" ilova zanjiri (docs/design/calls.md §3): {@code /api/calls/app/**} faqat shu zanjirda,
 * {@code @Order(2)} — Mini App zanjiridan ({@code @Order(1)}) keyin, admin zanjiridan oldin. Zanjir faqat qurilma
 * tokenini ({@code X-Device-Token}) biladi: admin JWT bu yerda 401, qurilma tokeni admin zanjirida 401.
 *
 * <ul>
 *   <li>{@code GET /api/calls/app/version} — ochiq (yangilanishni kirishdan oldin ham tekshirish);</li>
 *   <li>qolgani — {@link CallDeviceFilter#ROLE}.</li>
 * </ul>
 * Qurilmani ro'yxatdan o'tkazish ({@code POST /api/calls/devices/register}) — admin zanjirida, oddiy JWT bilan.
 */
@Configuration
public class CallsSecurityConfig {

    public static final String PATH = "/api/calls/app/**";

    @Bean
    @Order(2)
    public SecurityFilterChain callsAppSecurityFilterChain(HttpSecurity http,
                                                           CallDeviceService deviceService,
                                                           CallsProperties properties,
                                                           ObjectMapper objectMapper,
                                                           Messages messages) throws Exception {
        ErrorWriter errors = new ErrorWriter(objectMapper, messages);
        http
            .securityMatcher(PATH)
            .csrf(AbstractHttpConfigurer::disable)
            // Mobil ilova — brauzer emas, CORS kerak emas
            .cors(AbstractHttpConfigurer::disable)
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(HttpMethod.GET, "/api/calls/app/version").permitAll()
                .anyRequest().hasAuthority(CallDeviceFilter.ROLE))
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint((req, res, e) -> {
                    Object reason = req.getAttribute(CallDeviceFilter.REASON_ATTR);
                    errors.write(res, HttpStatus.UNAUTHORIZED.value(), HttpStatus.UNAUTHORIZED.getReasonPhrase(),
                        reason != null ? reason.toString() : "calls.device.unauthorized");
                })
                .accessDeniedHandler((req, res, e) ->
                    errors.write(res, HttpStatus.FORBIDDEN.value(), HttpStatus.FORBIDDEN.getReasonPhrase(),
                        "calls.forbidden")))
            .addFilterBefore(new CallDeviceFilter(deviceService, properties, errors),
                UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    /** {@code GlobalExceptionHandler.handleCoded} bilan bir xil shakl. */
    @RequiredArgsConstructor
    public static class ErrorWriter {

        private final ObjectMapper objectMapper;
        private final Messages messages;

        public void write(HttpServletResponse response, int status, String error, String code) throws IOException {
            response.setStatus(status);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            objectMapper.writeValue(response.getOutputStream(), ErrorResponse.builder()
                .timestamp(LocalDateTime.now())
                .status(status)
                .error(error)
                .message(messages.get(code))
                .code(code)
                .build());
        }
    }
}
