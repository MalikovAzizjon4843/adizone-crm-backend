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

    /**
     * Ruxsat etilgan frontend manbalari. {@code public static} — WebSocket
     * handshake CORS ni alohida tekshiradi va {@link WebSocketConfig} shu
     * ro'yxatni qayta ishlatadi, aks holda ikkita ro'yxat vaqt o'tib
     * bir-biridan ajralib qolardi.
     */
    public static final List<String> ALLOWED_ORIGIN_PATTERNS = List.of(
            "https://admin.adizone.uz",
            "https://app.adizone.uz",
            "https://adizone.uz",
            "https://www.adizone.uz",
            "https://*.vercel.app",
            "http://localhost:3000",
            "http://localhost:5173",
            "http://localhost:5174",
            "http://127.0.0.1:5173",
            "http://127.0.0.1:3000"
    );

    /**
     * Xodim rollari — "har qanday kirgan foydalanuvchi" o'rniga shu ro'yxat
     * ishlatiladi. STUDENT va PARENT uchun portal yo'q, ularning logini
     * bloklangan (phase5-audit Q1, U-02): eski token bilan kelsa ham
     * hech bir endpoint ochilmasin. Yangi xodim roli qo'shilsa — shu yerga.
     */
    public static final String[] STAFF_ROLES =
        {"SUPER_ADMIN", "ADMIN", "SALES_MANAGER", "ACCOUNTANT", "TEACHER"};

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
                // Ochiq registratsiya YO'Q: xodimni faqat SUPER_ADMIN/ADMIN
                // POST /api/users orqali yaratadi.
                .requestMatchers(
                    "/api/auth/login",
                    "/api/auth/refresh",
                    "/api/auth/logout"
                ).permitAll()
                // Meta webhook: Meta bizning JWT imizni bilmaydi. GET -
                // verifikatsiya qo'l berishi, POST - leadgen xabarlari.
                // Himoya token emas, X-Hub-Signature-256 imzosi
                // (MetaWebhookController). Faqat shu bitta yo'l ochiq:
                // qolgan /api/meta/** ostidagi sozlash endpointlari
                // quyida ADMIN bilan cheklangan.
                .requestMatchers("/api/meta/webhook").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/files/**").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/leads/public").permitAll()
                // Faqat shu bitta yo'l ochiq — login sahifasi o'quv yilini ko'rsatadi.
                // Butun /api/settings/** ni ochiq qoldirmaymiz: kelajakda qo'shiladigan
                // POST/PUT avtomatik ravishda ommaviy bo'lib qolmasin.
                .requestMatchers(HttpMethod.GET, "/api/settings/academic-year").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/settings/**").hasAnyRole(STAFF_ROLES)
                // Markaz rekvizitlari — faqat SUPER_ADMIN (leaves-exams-contracts §5.2, D12)
                .requestMatchers("/api/settings/center").hasRole("SUPER_ADMIN")
                .requestMatchers("/api/settings/**").hasAnyRole("SUPER_ADMIN", "ADMIN")
                .requestMatchers("/actuator/health").permitAll()
                // WebSocket qo'l berishi: Authorization sarlavhasi yo'q, chunki
                // brauzer WebSocket'ga sarlavha qo'sha olmaydi. Token shu yerda
                // emas, JwtHandshakeInterceptor'da (?token=...) tekshiriladi va
                // tokensiz ulanish 403 bilan rad etiladi.
                .requestMatchers("/ws", "/ws/**").permitAll()
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
                // Imtihon yozilishlari: pullik imtihon to'lovi kassaga — buxgalter ham (leaves-exams-contracts §4)
                .requestMatchers(HttpMethod.GET, "/api/exams/*/registrations")
                    .hasAnyRole("SUPER_ADMIN", "ADMIN", "ACCOUNTANT", "TEACHER")
                .requestMatchers(HttpMethod.POST, "/api/exams/*/registrations")
                    .hasAnyRole("SUPER_ADMIN", "ADMIN", "ACCOUNTANT", "TEACHER")
                .requestMatchers(HttpMethod.POST, "/api/exams/*/registrations/*/cancel")
                    .hasAnyRole("SUPER_ADMIN", "ADMIN", "ACCOUNTANT")
                .requestMatchers(HttpMethod.GET, "/api/exams/**")
                    .hasAnyRole("SUPER_ADMIN", "ADMIN", "TEACHER")
                .requestMatchers(HttpMethod.GET, "/api/notices/**").hasAnyRole(STAFF_ROLES)

                .requestMatchers(HttpMethod.POST, "/api/students/*/transfer-group")
                    .hasAnyRole("SUPER_ADMIN", "ADMIN")

                // ── Role-based access ──
                // Direktor dashboardi: ACC moliya bo'limini ko'radi, bo'limlar servisda rol bo'yicha (§4)
                .requestMatchers("/api/dashboard/director", "/api/dashboard/director/**")
                    .hasAnyRole("SUPER_ADMIN", "ADMIN", "ACCOUNTANT")
                .requestMatchers("/api/dashboard/**")
                    .hasAnyRole("SUPER_ADMIN", "ADMIN")
                .requestMatchers("/api/analytics/**")
                    .hasAnyRole("SUPER_ADMIN", "ADMIN")
                .requestMatchers("/api/finance/**")
                    .hasAnyRole("SUPER_ADMIN", "ADMIN", "ACCOUNTANT")
                .requestMatchers("/api/expenses", "/api/expenses/**")
                    .hasAnyRole("SUPER_ADMIN", "ADMIN", "ACCOUNTANT")
                .requestMatchers("/api/payroll/**")
                    .hasAnyRole("SUPER_ADMIN", "ADMIN", "ACCOUNTANT")
                .requestMatchers("/api/salary-rules", "/api/salary-rules/**")
                    .hasRole("SUPER_ADMIN")
                .requestMatchers("/api/payments/**")
                    .hasAnyRole("SUPER_ADMIN", "ADMIN", "SALES_MANAGER", "ACCOUNTANT")
                // Kassani o'chirish — faqat ma'muriyat. Umumiy kassa qoidasidan
                // OLDIN: aks holda u pastdagi "DELETE /api/**" gacha yetmay
                // ACCOUNTANT ga ham o'chirishni ochib qo'yardi.
                .requestMatchers(HttpMethod.DELETE, "/api/cash-registers/**")
                    .hasAnyRole("SUPER_ADMIN", "ADMIN")
                .requestMatchers("/api/cash-registers/**")
                    .hasAnyRole("SUPER_ADMIN", "ADMIN", "ACCOUNTANT")
                // Bonus/jarima: o'chirish — ma'muriyat, qolgani — buxgalter ham
                .requestMatchers(HttpMethod.DELETE, "/api/bonus-penalties/**")
                    .hasAnyRole("SUPER_ADMIN", "ADMIN")
                .requestMatchers("/api/bonus-penalties", "/api/bonus-penalties/**")
                    .hasAnyRole("SUPER_ADMIN", "ADMIN", "ACCOUNTANT")
                .requestMatchers("/api/leads/**")
                    .hasAnyRole("SUPER_ADMIN", "ADMIN", "SALES_MANAGER")
                .requestMatchers("/api/tasks/**")
                    .hasAnyRole("SUPER_ADMIN", "ADMIN", "SALES_MANAGER")
                // Webhook yuqorida permitAll qilingan va u shu qatordan
                // OLDIN turibdi - Spring birinchi mos kelgan qoidani
                // qo'llaydi, ya'ni tartibni buzmang.
                .requestMatchers("/api/meta/**")
                    .hasAnyRole("SUPER_ADMIN", "ADMIN")
                // Bosqichlarni o'qish barcha xodimlarga ochiq — kanban, lid kartasi va
                // filtrlar nomlarni shu yerdan oladi. Yozish controllerdagi
                // metod darajasidagi @PreAuthorize bilan cheklangan.
                .requestMatchers("/api/lead-stages/**").hasAnyRole(STAFF_ROLES)
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
                .requestMatchers("/api/contracts", "/api/contracts/**",
                        "/api/contract-templates", "/api/contract-templates/**")
                    .hasAnyRole("SUPER_ADMIN", "ADMIN")
                .requestMatchers("/api/marketing/**")
                    .hasAnyRole("SUPER_ADMIN", "ADMIN")
                // Audit: obyekt tarixi — ma'muriyat, umumiy jurnal — faqat SUPER_ADMIN
                .requestMatchers(HttpMethod.GET, "/api/audit-logs/entity/**")
                    .hasAnyRole("SUPER_ADMIN", "ADMIN")
                .requestMatchers("/api/audit-logs", "/api/audit-logs/**")
                    .hasRole("SUPER_ADMIN")

                // Ta'tillar (leaves-exams-contracts §1): ariza, o'z arizalari, bekor qilish,
                // hisobot — barcha xodimlar (egalik LeaveService da); ro'yxat, tasdiqlash/rad,
                // darslar — faqat ma'muriyat. /pending, /teacher/** — "/api/leaves/*" dan OLDIN,
                // aks holda u ularni ham ochib qo'yardi.
                .requestMatchers(HttpMethod.GET, "/api/leaves/pending", "/api/leaves/pending/**",
                        "/api/leaves/teacher/**")
                    .hasAnyRole("SUPER_ADMIN", "ADMIN")
                .requestMatchers(HttpMethod.GET, "/api/leaves/*", "/api/leaves/user/*")
                    .hasAnyRole(STAFF_ROLES)
                .requestMatchers(HttpMethod.POST, "/api/leaves", "/api/leaves/*/cancel")
                    .hasAnyRole(STAFF_ROLES)
                .requestMatchers("/api/leaves", "/api/leaves/**")
                    .hasAnyRole("SUPER_ADMIN", "ADMIN")
                // "Darsni X o'tdi" (§2.2): ro'yxat — buxgalter ham (oylik), /my — o'qituvchi
                .requestMatchers(HttpMethod.GET, "/api/substitutions/my").hasRole("TEACHER")
                .requestMatchers(HttpMethod.GET, "/api/substitutions", "/api/substitutions/**")
                    .hasAnyRole("SUPER_ADMIN", "ADMIN", "ACCOUNTANT")
                .requestMatchers("/api/substitutions", "/api/substitutions/**")
                    .hasAnyRole("SUPER_ADMIN", "ADMIN")

                .requestMatchers(HttpMethod.GET, "/api/students", "/api/students/**")
                    .hasAnyRole("SUPER_ADMIN", "ADMIN", "SALES_MANAGER", "ACCOUNTANT", "TEACHER")
                .requestMatchers("/api/students/**")
                    .hasAnyRole("SUPER_ADMIN", "ADMIN", "SALES_MANAGER", "ACCOUNTANT")

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
                // O'qituvchilar: /me/** yuqorida TEACHER ga ochilgan. KPI — faqat
                // ma'muriyat; ro'yxat/detal/qidiruv — buxgalter ham (oylik,
                // bonus/jarima sahifalari). STUDENT/PARENT/SALES_MANAGER — yo'q.
                .requestMatchers(HttpMethod.GET,
                        "/api/teachers/kpi/**", "/api/teachers/*/kpi", "/api/teachers/*/kpi/**")
                    .hasAnyRole("SUPER_ADMIN", "ADMIN")
                .requestMatchers(HttpMethod.GET, "/api/teachers", "/api/teachers/**")
                    .hasAnyRole("SUPER_ADMIN", "ADMIN", "ACCOUNTANT")
                .requestMatchers("/api/teachers", "/api/teachers/**")
                    .hasAnyRole("SUPER_ADMIN", "ADMIN")

                // Classroom writes — after GET matcher above
                .requestMatchers("/api/classrooms/**")
                    .hasAnyRole("SUPER_ADMIN", "ADMIN")

                // Notice read-status — har qanday xodim (admin-only POST dan oldin)
                .requestMatchers(HttpMethod.POST, "/api/notices/read-all", "/api/notices/*/read")
                    .hasAnyRole(STAFF_ROLES)
                .requestMatchers(HttpMethod.POST, "/api/notices/**")
                    .hasAnyRole("SUPER_ADMIN", "ADMIN")
                .requestMatchers(HttpMethod.PUT, "/api/notices/**")
                    .hasAnyRole("SUPER_ADMIN", "ADMIN")

                // Ichki chat — faqat xodimlar (Q13: STUDENT/PARENT yo'q).
                // Suhbatga kirish huquqi a'zolik bo'yicha ChatAccessService'da.
                .requestMatchers("/api/chat/**").hasAnyRole(STAFF_ROLES)
                .requestMatchers("/api/search/**").hasAnyRole(STAFF_ROLES)
                .requestMatchers(HttpMethod.POST, "/api/files/**").hasAnyRole(STAFF_ROLES)

                .requestMatchers(HttpMethod.DELETE, "/api/**")
                    .hasAnyRole("SUPER_ADMIN", "ADMIN")

                // Qolgan hammasi — faqat xodimlar (U-02): eski "authenticated" STUDENT/PARENT
                // tokeniga ham ochiq edi (masalan /api/auth/me, /api/enums/**).
                .anyRequest().hasAnyRole(STAFF_ROLES)
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

        config.setAllowedOriginPatterns(ALLOWED_ORIGIN_PATTERNS);

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
