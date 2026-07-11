package com.crm.config;

import com.crm.security.CustomUserDetailsService;
import com.crm.security.jwt.JwtAuthenticationFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.crm.dto.response.ApiResponse;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthFilter;
    private final CustomUserDetailsService userDetailsService;
    private final ObjectMapper objectMapper;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .authorizeHttpRequests(auth -> auth
                // Preflight must be first — otherwise browsers hang ~30s on CORS
                .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()

                // ── Public endpoints (no JWT needed) ──
                .requestMatchers(
                    "/api/auth/login",
                    "/api/auth/register",
                    "/api/auth/refresh",
                    "/api/auth/logout"
                ).permitAll()
                .requestMatchers(HttpMethod.GET, "/api/files/**").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/leads/public").permitAll()
                .requestMatchers("/api/settings/**").permitAll()
                .requestMatchers("/actuator/health").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/notices/latest").permitAll()

                // ── Teacher-accessible reads (before broader / catch-alls) ──
                .requestMatchers(HttpMethod.GET, "/api/timetable/grid")
                    .hasAnyRole("SUPER_ADMIN", "ADMIN", "TEACHER")
                .requestMatchers(HttpMethod.GET, "/api/timetable/**")
                    .hasAnyRole("SUPER_ADMIN", "ADMIN", "TEACHER")
                .requestMatchers(HttpMethod.GET, "/api/classrooms", "/api/classrooms/**")
                    .hasAnyRole("SUPER_ADMIN", "ADMIN", "TEACHER")
                .requestMatchers(HttpMethod.GET, "/api/groups/**")
                    .hasAnyRole("SUPER_ADMIN", "ADMIN", "ACCOUNTANT", "TEACHER")
                .requestMatchers(HttpMethod.GET, "/api/courses/**")
                    .hasAnyRole("SUPER_ADMIN", "ADMIN", "TEACHER")
                .requestMatchers(HttpMethod.GET, "/api/exams/**")
                    .hasAnyRole("SUPER_ADMIN", "ADMIN", "TEACHER")
                .requestMatchers(HttpMethod.GET, "/api/notices/**").authenticated()

                .requestMatchers(HttpMethod.POST, "/api/students/*/transfer-group")
                    .hasAnyRole("SUPER_ADMIN", "ADMIN")

                // ── Role-based access ──
                .requestMatchers("/api/analytics/**")
                    .hasAnyRole("SUPER_ADMIN", "ADMIN")
                .requestMatchers("/api/finance/**")
                    .hasAnyRole("SUPER_ADMIN", "ADMIN", "ACCOUNTANT")
                .requestMatchers("/api/payroll/**")
                    .hasAnyRole("SUPER_ADMIN", "ADMIN", "ACCOUNTANT")
                .requestMatchers("/api/payments/**")
                    .hasAnyRole("SUPER_ADMIN", "ADMIN", "ACCOUNTANT")
                .requestMatchers("/api/cash-registers/**")
                    .hasAnyRole("SUPER_ADMIN", "ADMIN", "ACCOUNTANT")
                .requestMatchers("/api/leads/**")
                    .hasAnyRole("SUPER_ADMIN", "ADMIN")
                .requestMatchers("/api/promotions/**")
                    .hasAnyRole("SUPER_ADMIN", "ADMIN")
                .requestMatchers("/api/parents/**")
                    .hasAnyRole("SUPER_ADMIN", "ADMIN", "ACCOUNTANT")
                .requestMatchers("/api/users/**")
                    .hasAnyRole("SUPER_ADMIN", "ADMIN")
                .requestMatchers("/api/import/**")
                    .hasAnyRole("SUPER_ADMIN", "ADMIN")
                .requestMatchers("/api/roles/**")
                    .hasAnyRole("SUPER_ADMIN", "ADMIN")
                .requestMatchers("/api/admin/**")
                    .hasRole("SUPER_ADMIN")

                .requestMatchers("/api/attendance/**")
                    .hasAnyRole("SUPER_ADMIN", "ADMIN", "TEACHER")
                .requestMatchers("/api/homework/**")
                    .hasAnyRole("SUPER_ADMIN", "ADMIN", "TEACHER")
                .requestMatchers(HttpMethod.POST, "/api/exams/**")
                    .hasAnyRole("SUPER_ADMIN", "ADMIN", "TEACHER")
                .requestMatchers(HttpMethod.PUT, "/api/exams/**")
                    .hasAnyRole("SUPER_ADMIN", "ADMIN", "TEACHER")
                .requestMatchers("/api/teachers/me/**")
                    .hasRole("TEACHER")
                .requestMatchers("/api/teacher/**")
                    .hasRole("TEACHER")

                // Classroom writes — after GET matcher above
                .requestMatchers("/api/classrooms/**")
                    .hasAnyRole("SUPER_ADMIN", "ADMIN")

                .requestMatchers(HttpMethod.POST, "/api/notices/**")
                    .hasAnyRole("SUPER_ADMIN", "ADMIN")
                .requestMatchers(HttpMethod.PUT, "/api/notices/**")
                    .hasAnyRole("SUPER_ADMIN", "ADMIN")

                .requestMatchers("/api/search/**").authenticated()
                .requestMatchers(HttpMethod.POST, "/api/files/**").authenticated()

                .requestMatchers(HttpMethod.DELETE, "/api/**")
                    .hasAnyRole("SUPER_ADMIN", "ADMIN")

                .anyRequest().authenticated()
            )
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint((request, response, authException) -> {
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                    response.setCharacterEncoding("UTF-8");
                    objectMapper.writeValue(response.getOutputStream(),
                        ApiResponse.error("Avtorizatsiya talab qilinadi"));
                })
                .accessDeniedHandler((request, response, accessDeniedException) -> {
                    response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                    response.setCharacterEncoding("UTF-8");
                    objectMapper.writeValue(response.getOutputStream(),
                        ApiResponse.error("Ruxsat yo'q"));
                })
            )
            .sessionManagement(sess -> sess.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authenticationProvider(authenticationProvider())
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();

        config.setAllowedOriginPatterns(List.of(
                "https://admin.adizone.uz",
                "https://adizone.uz",
                "https://www.adizone.uz",
                "https://*.vercel.app",
                "http://localhost:3000",
                "http://localhost:5173",
                "http://localhost:5174",
                "http://127.0.0.1:5173",
                "http://127.0.0.1:3000"
        ));

        config.setAllowedMethods(Arrays.asList(
                "GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"
        ));
        config.setAllowedHeaders(List.of("*"));
        config.setExposedHeaders(List.of("Authorization", "Content-Type"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    @Bean
    public AuthenticationProvider authenticationProvider() {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder());
        return provider;
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
