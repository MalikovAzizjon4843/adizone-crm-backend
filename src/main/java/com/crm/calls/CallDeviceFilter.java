package com.crm.calls;

import com.crm.entity.User;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Ilova zanjiri filtri ({@code /api/calls/app/**}). Bean EMAS — servlet konteyneriga global filtr bo'lib ro'yxatdan
 * o'tmasin; faqat {@link CallsSecurityConfig} zanjirida.
 *
 * <p>{@code X-Device-Token} har so'rovda bazadan tekshiriladi: qurilma faol (revoke qilinmagan), egasi faol va
 * ruxsat etilgan rolda. Rad etish sababi {@link #REASON_ATTR} da — entry point aniq kod qaytaradi
 * ({@code calls.device.revoked} — ilova "qayta kiring" ekraniga o'tadi).
 *
 * <p>{@code X-App-Version} berilsa va {@code app.calls.app.min-supported-version} dan past bo'lsa — 426
 * {@code calls.app.upgradeRequired} ({@code GET /api/calls/app/version} bundan mustasno).
 */
@RequiredArgsConstructor
public class CallDeviceFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Device-Token";
    public static final String VERSION_HEADER = "X-App-Version";
    public static final String ROLE = "ROLE_CALL_DEVICE";
    public static final String REASON_ATTR = CallDeviceFilter.class.getName() + ".reason";

    private final CallDeviceService deviceService;
    private final CallsProperties properties;
    private final CallsSecurityConfig.ErrorWriter errorWriter;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod()) || isVersionEndpoint(request)) {
            chain.doFilter(request, response);
            return;
        }
        String version = request.getHeader(VERSION_HEADER);
        if (version != null && !version.isBlank()
                && AppVersions.compare(version, properties.getApp().getMinSupportedVersion()) < 0) {
            errorWriter.write(response, 426, "Upgrade Required", "calls.app.upgradeRequired");
            return;
        }
        String token = request.getHeader(HEADER);
        if (token != null && !token.isBlank()) {
            CallDeviceService.AuthResult result = deviceService.authenticate(token.trim());
            if (result.ok()) {
                User user = result.user();
                var auth = new UsernamePasswordAuthenticationToken(
                    new CallDevicePrincipal(result.device().getId(), user.getId(), user.getUsername()), null,
                    List.of(new SimpleGrantedAuthority(ROLE), new SimpleGrantedAuthority("ROLE_" + user.getRole().name())));
                SecurityContextHolder.getContext().setAuthentication(auth);
            } else {
                request.setAttribute(REASON_ATTR, result.reasonCode());
            }
        }
        chain.doFilter(request, response);
    }

    private static boolean isVersionEndpoint(HttpServletRequest request) {
        return "GET".equalsIgnoreCase(request.getMethod())
            && (request.getContextPath() + "/api/calls/app/version").equals(request.getRequestURI());
    }
}
