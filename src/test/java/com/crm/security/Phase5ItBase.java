package com.crm.security;

import com.crm.billing.support.AbstractBillingIT;
import com.crm.entity.User;
import com.crm.entity.enums.UserRole;
import com.crm.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;

/**
 * Phase 5 xavfsizlik bloki testlari (docs/audit/phase5-audit.md §12.1) uchun umumiy yordamchilar.
 *
 * <p>{@code users} jadvali {@code fixtures.wipe()} da tozalanmaydi — shuning uchun har test
 * o'z foydalanuvchisini noyob login bilan yaratadi.
 */
abstract class Phase5ItBase extends AbstractBillingIT {

    static final String PASSWORD = "Parol12345";
    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired
    protected UserRepository userRepository;
    @Autowired
    protected PasswordEncoder passwordEncoder;
    @Autowired
    protected JdbcTemplate jdbc;

    /** Bazada haqiqiy foydalanuvchi (parol {@link #PASSWORD}). */
    protected User newUser(UserRole role) {
        String username = "p5-" + role.name().toLowerCase(Locale.ROOT) + "-" + SEQ.incrementAndGet()
            + "-" + System.nanoTime() % 100_000;
        return inTx(() -> userRepository.save(User.builder()
            .username(username)
            .password(passwordEncoder.encode(PASSWORD))
            .firstName("Test")
            .lastName(role.name())
            .role(role)
            .isActive(true)
            .build()));
    }

    /** MockMvc so'rovi shu foydalanuvchi nomidan (JWT filtrisiz). */
    protected static RequestPostProcessor as(User u) {
        return user(u.getUsername()).roles(u.getRole().name());
    }

    /** Servisni to'g'ridan-to'g'ri chaqirish uchun SecurityContext. */
    protected static void loginAs(User u) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
            u.getUsername(), null, List.of(new SimpleGrantedAuthority("ROLE_" + u.getRole().name()))));
    }

    protected User reload(User u) {
        return userRepository.findById(u.getId()).orElseThrow();
    }

    protected boolean isPostgres() {
        return Boolean.TRUE.equals(jdbc.execute((ConnectionCallback<Boolean>) c ->
            c.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT).contains("postgres")));
    }
}
