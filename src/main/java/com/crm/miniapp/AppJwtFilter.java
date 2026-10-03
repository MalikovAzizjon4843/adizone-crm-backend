package com.crm.miniapp;

import com.crm.entity.AppIdentity;
import com.crm.repository.AppIdentityRepository;
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
 * App zanjiri filtri ({@code /api/app/**}). Bean EMAS — servlet konteyneriga global filtr bo'lib
 * ro'yxatdan o'tmasin; faqat {@link AppSecurityConfig} zanjirida.
 *
 * <p>Token to'g'ri bo'lsa ham identity har so'rovda bazadan tekshiriladi: {@code ACTIVE} va
 * {@code identity_version == iv}. Uzilgan identity'ning tokeni darhol 401.
 */
@RequiredArgsConstructor
public class AppJwtFilter extends OncePerRequestFilter {

    public static final String ROLE = "ROLE_APP";

    private final AppJwtService jwtService;
    private final AppIdentityRepository identityRepository;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            jwtService.parse(header.substring(7)).ifPresent(token -> {
                AppIdentity identity = identityRepository.findById(token.identityId()).orElse(null);
                if (identity != null && identity.isActive()
                        && identity.getIdentityVersion() == token.identityVersion()) {
                    var auth = new UsernamePasswordAuthenticationToken(
                        new AppPrincipal(identity.getId(), identity.getKind()), null,
                        List.of(new SimpleGrantedAuthority(ROLE)));
                    SecurityContextHolder.getContext().setAuthentication(auth);
                }
            });
        }
        chain.doFilter(request, response);
    }
}
