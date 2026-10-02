package com.crm.security;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.User;

import java.util.Collection;

/**
 * Spring {@link User} + token versiyasi.
 *
 * <p>Access token ichida {@code tv} (token version) claim'i bo'ladi. Parol
 * almashtirilsa yoki tiklansa {@code users.token_version} oshadi va eski
 * tokenlar keyingi so'rovdayoq yaroqsiz bo'ladi (phase5-audit U-05). Bu
 * klass bazadagi joriy versiyani {@link com.crm.security.jwt.JwtUtils} ga
 * yetkazadi — har so'rovda user baribir bazadan yuklanadi.
 */
public class CrmUserDetails extends User {

    private final int tokenVersion;

    public CrmUserDetails(String username, String password,
                          Collection<? extends GrantedAuthority> authorities, int tokenVersion) {
        super(username, password, authorities);
        this.tokenVersion = tokenVersion;
    }

    public int getTokenVersion() {
        return tokenVersion;
    }
}
