package com.crm.miniapp;

import com.crm.entity.AppIdentity;
import com.crm.exception.CodedException;
import com.crm.telegram.TelegramProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;

/**
 * Mini App JWT (docs/design/telegram-platform.md §3.3) — admin JWT'dan alohida:
 * <ul>
 *   <li>kalit {@code TELEGRAM_APP_JWT_SECRET} (≥ 32 bayt, UTF-8), admin {@code JWT_SECRET} emas;</li>
 *   <li>{@code sub} = {@code app_identities.id}, {@code typ=app}, {@code aud=adizone-app}, {@code kind},
 *       {@code iv} (identity versiyasi — uzishda oshadi);</li>
 *   <li>muddat 30 daqiqa, refresh yo'q — Mini App initData bilan qayta {@code POST /api/app/auth} qiladi.</li>
 * </ul>
 * Vaqt {@code billingClock} dan — testlar soatni boshqaradi.
 */
@Component
@RequiredArgsConstructor
public class AppJwtService {

    public static final String TYPE = "app";
    public static final String AUDIENCE = "adizone-app";
    static final String CLAIM_TYPE = "typ";
    static final String CLAIM_KIND = "kind";
    static final String CLAIM_VERSION = "iv";
    static final String CLAIM_ROLES = "roles";
    private static final int MIN_SECRET_BYTES = 32;

    private final TelegramProperties properties;
    private final Clock billingClock;

    /** Tekshirilgan token mazmuni. */
    public record AppToken(Long identityId, AppIdentity.Kind kind, int identityVersion) {
    }

    public boolean isConfigured() {
        String secret = properties.getApp().getJwtSecret();
        return secret != null && secret.getBytes(StandardCharsets.UTF_8).length >= MIN_SECRET_BYTES;
    }

    public long ttlSeconds() {
        return properties.getApp().getTokenTtl().getSeconds();
    }

    /**
     * @param roles {@code STUDENT}/{@code PARENT}/{@code TEACHER} — ma'lumot uchun (frontend menyusi); huquq
     *              tekshiruvi token'dan emas, bazadan ({@link AppJwtFilter})
     */
    public String issue(AppIdentity identity, java.util.List<String> roles) {
        Instant now = billingClock.instant();
        return Jwts.builder()
            .setSubject(String.valueOf(identity.getId()))
            .setAudience(AUDIENCE)
            .claim(CLAIM_TYPE, TYPE)
            .claim(CLAIM_KIND, identity.getKind().name())
            .claim(CLAIM_ROLES, roles)
            .claim(CLAIM_VERSION, identity.getIdentityVersion())
            .setIssuedAt(Date.from(now))
            .setExpiration(Date.from(now.plus(properties.getApp().getTokenTtl())))
            .signWith(key(), SignatureAlgorithm.HS256)
            .compact();
    }

    /** Imzo, muddat, {@code aud} va {@code typ} to'g'ri bo'lsa — mazmun; aks holda bo'sh. */
    public Optional<AppToken> parse(String token) {
        if (!isConfigured() || token == null || token.isBlank()) {
            return Optional.empty();
        }
        try {
            Claims claims = Jwts.parserBuilder()
                .setSigningKey(key())
                .requireAudience(AUDIENCE)
                .require(CLAIM_TYPE, TYPE)
                .setClock(() -> Date.from(billingClock.instant()))
                .build()
                .parseClaimsJws(token)
                .getBody();
            Integer version = claims.get(CLAIM_VERSION, Integer.class);
            String kind = claims.get(CLAIM_KIND, String.class);
            if (version == null || kind == null) {
                return Optional.empty();
            }
            return Optional.of(new AppToken(Long.valueOf(claims.getSubject()),
                AppIdentity.Kind.valueOf(kind), version));
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private Key key() {
        if (!isConfigured()) {
            throw new CodedException(HttpStatus.SERVICE_UNAVAILABLE, "app.auth.notConfigured");
        }
        return Keys.hmacShaKeyFor(properties.getApp().getJwtSecret().getBytes(StandardCharsets.UTF_8));
    }
}
