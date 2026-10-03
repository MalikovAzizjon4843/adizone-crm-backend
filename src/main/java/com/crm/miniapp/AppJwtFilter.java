package com.crm.miniapp;

import com.crm.entity.AppIdentity;
import com.crm.entity.User;
import com.crm.entity.enums.UserRole;
import com.crm.repository.AppIdentityRepository;
import com.crm.repository.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * App zanjiri filtri ({@code /api/app/**}). Bean EMAS — servlet konteyneriga global filtr bo'lib
 * ro'yxatdan o'tmasin; faqat {@link AppSecurityConfig} zanjirida.
 *
 * <p>Token to'g'ri bo'lsa ham identity har so'rovda bazadan tekshiriladi: {@code ACTIVE} va
 * {@code identity_version == iv}. Uzilgan identity'ning tokeni darhol 401.
 *
 * <p>O'qituvchi rejimi ({@link #ROLE_TEACHER}) ham token'dan emas, bazadan: identity'ga bog'langan xodim
 * hozir ham faol va TEACHER bo'lsagina (telegram-platform §11.4, D9).
 */
@RequiredArgsConstructor
public class AppJwtFilter extends OncePerRequestFilter {

    public static final String ROLE = "ROLE_APP";
    public static final String ROLE_TEACHER = "ROLE_APP_TEACHER";

    private final AppJwtService jwtService;
    private final AppIdentityRepository identityRepository;
    private final UserRepository userRepository;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            jwtService.parse(header.substring(7)).ifPresent(token -> {
                AppIdentity identity = identityRepository.findById(token.identityId()).orElse(null);
                if (identity != null && identity.isActive()
                        && identity.getIdentityVersion() == token.identityVersion()) {
                    Long staffUserId = activeTeacherUserId(identity);
                    List<GrantedAuthority> authorities = new ArrayList<>();
                    authorities.add(new SimpleGrantedAuthority(ROLE));
                    if (staffUserId != null) {
                        authorities.add(new SimpleGrantedAuthority(ROLE_TEACHER));
                    }
                    var auth = new UsernamePasswordAuthenticationToken(
                        new AppPrincipal(identity.getId(), identity.getKind(), staffUserId), null, authorities);
                    SecurityContextHolder.getContext().setAuthentication(auth);
                }
            });
        }
        chain.doFilter(request, response);
    }

    private Long activeTeacherUserId(AppIdentity identity) {
        if (identity.getStaffUserId() == null) {
            return null;
        }
        return userRepository.findById(identity.getStaffUserId())
            .filter(u -> Boolean.TRUE.equals(u.getIsActive()) && u.getRole() == UserRole.TEACHER)
            .map(User::getId)
            .orElse(null);
    }
}
