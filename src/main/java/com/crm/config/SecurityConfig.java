package com.crm.config;

import com.crm.security.CustomUserDetailsService;
import com.crm.security.jwt.JwtAuthenticationFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
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

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .authorizeHttpRequests(auth -> auth
                // ── Public endpoints (no JWT needed) ──
                .requestMatchers("/api/auth/login").permitAll()
                .requestMatchers("/api/auth/refresh").permitAll()
                .requestMatchers("/api/auth/logout").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/files/**").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/leads/public").permitAll()
                .requestMatchers("/api/settings/**").permitAll()
                .requestMatchers("/actuator/health").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/notices/latest").permitAll()

                // ── Role-based access ──
                .requestMatchers("/api/analytics/**")
                    .hasAnyRole("SUPER_ADMIN", "ADMIN")
                .requestMatchers("/api/finance/**")
                    .hasAnyRole("SUPER_ADMIN", "ADMIN", "ACCOUNTANT")
                .requestMatchers("/api/payroll/**")
                    .hasAnyRole("SUPER_ADMIN", "ADMIN", "ACCOUNTANT")
                .requestMatchers("/api/payments/**")
                    .hasAnyRole("SUPER_ADMIN", "ADMIN", "ACCOUNTANT")
                .requestMatchers("/api/leads/**")
                    .hasAnyRole("SUPER_ADMIN", "ADMIN")
                .requestMatchers("/api/promotions/**")
                    .hasAnyRole("SUPER_ADMIN", "ADMIN")
                .requestMatchers("/api/parents/**")
                    .hasAnyRole("SUPER_ADMIN", "ADMIN", "ACCOUNTANT")
                .requestMatchers("/api/users/**")
                    .hasAnyRole("SUPER_ADMIN", "ADMIN")
                .requestMatchers("/api/roles/**")
                    .hasAnyRole("SUPER_ADMIN", "ADMIN")

                .requestMatchers("/api/exams/**").authenticated()
                .requestMatchers("/api/homework/**").authenticated()

                .requestMatchers(HttpMethod.GET, "/api/notices/**").authenticated()
                .requestMatchers(HttpMethod.POST, "/api/notices/**")
                    .hasAnyRole("SUPER_ADMIN", "ADMIN")

                .requestMatchers("/api/search/**").authenticated()
                .requestMatchers(HttpMethod.POST, "/api/files/**").authenticated()

                .requestMatchers(HttpMethod.DELETE, "/api/**")
                    .hasAnyRole("SUPER_ADMIN", "ADMIN")

                .anyRequest().authenticated()
            )
            .sessionManagement(sess -> sess.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authenticationProvider(authenticationProvider())
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();

        // Faqat BU BITTA setAllowedOriginPatterns bo'lishi kerak
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
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source =
                new UrlBasedCorsConfigurationSource();
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
