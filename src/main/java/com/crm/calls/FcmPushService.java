package com.crm.calls;

import com.crm.entity.CallDevice;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * FCM push — CRM'dan qo'ng'iroq so'rovi telefonga darhol yetsin (docs/design/calls.md §6.2). Standart O'CHIQ
 * ({@code app.calls.fcm.enabled=false}): ilova {@code GET /api/calls/app/requests/pending} ni so'rab turadi.
 *
 * <p>FCM HTTP v1: service account JSON ({@code app.calls.fcm.credentials-file}, sir — repoda emas) bilan OAuth2
 * JWT-bearer → access token (≈1 soat, keshda) → {@code messages:send}, faqat {@code data} xabari (ilova o'zi ko'rsatadi).
 * Yuborish fon oqimida; xato faqat logga — so'rov baribir PENDING, ilova pending orqali oladi.
 */
@Service
@Slf4j
public class FcmPushService {

    private static final String SCOPE = "https://www.googleapis.com/auth/firebase.messaging";

    private final CallsProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "fcm-push");
        t.setDaemon(true);
        return t;
    });

    private volatile String accessToken;
    private volatile Instant accessTokenExpiresAt = Instant.EPOCH;

    public FcmPushService(CallsProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public boolean enabled() {
        CallsProperties.Fcm fcm = properties.getFcm();
        return fcm.isEnabled() && !fcm.getProjectId().isBlank() && !fcm.getCredentialsFile().isBlank();
    }

    public void sendCallRequest(CallDevice device, Long requestId, String phone, String contactName) {
        if (!enabled() || device == null || device.getFcmToken() == null) {
            return;
        }
        Map<String, String> data = new LinkedHashMap<>();
        data.put("type", "CALL_REQUEST");
        data.put("requestId", String.valueOf(requestId));
        data.put("phone", phone);
        if (contactName != null) {
            data.put("contactName", contactName);
        }
        String token = device.getFcmToken();
        executor.submit(() -> send(token, data, requestId));
    }

    private void send(String fcmToken, Map<String, String> data, Long requestId) {
        try {
            Map<String, Object> message = Map.of("message", Map.of(
                "token", fcmToken,
                "data", data,
                "android", Map.of("priority", "HIGH", "ttl", properties.getRequestTtlSeconds() + "s")));
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://fcm.googleapis.com/v1/projects/" + properties.getFcm().getProjectId()
                    + "/messages:send"))
                .timeout(Duration.ofSeconds(15))
                .header("Authorization", "Bearer " + accessToken())
                .header("Content-Type", "application/json; charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(message)))
                .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                log.warn("FCM: so'rov #{} yuborilmadi — HTTP {}: {}", requestId, response.statusCode(),
                    abbreviate(response.body()));
            }
        } catch (Exception e) {
            log.warn("FCM: so'rov #{} yuborilmadi — {}", requestId, e.getMessage());
        }
    }

    private synchronized String accessToken() throws Exception {
        if (accessToken != null && Instant.now().isBefore(accessTokenExpiresAt.minusSeconds(60))) {
            return accessToken;
        }
        JsonNode creds = objectMapper.readTree(Files.readString(Path.of(properties.getFcm().getCredentialsFile())));
        String tokenUri = creds.path("token_uri").asText("https://oauth2.googleapis.com/token");
        Instant now = Instant.now();
        String assertion = Jwts.builder()
            .setIssuer(creds.path("client_email").asText())
            .setAudience(tokenUri)
            .claim("scope", SCOPE)
            .setIssuedAt(Date.from(now))
            .setExpiration(Date.from(now.plusSeconds(3600)))
            .signWith(privateKey(creds.path("private_key").asText()), SignatureAlgorithm.RS256)
            .compact();
        String form = "grant_type=" + URLEncoder.encode("urn:ietf:params:oauth:grant-type:jwt-bearer",
            StandardCharsets.UTF_8) + "&assertion=" + URLEncoder.encode(assertion, StandardCharsets.UTF_8);
        HttpResponse<String> response = http.send(HttpRequest.newBuilder()
                .uri(URI.create(tokenUri))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build(),
            HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException("OAuth2 token: HTTP " + response.statusCode());
        }
        JsonNode body = objectMapper.readTree(response.body());
        accessToken = body.path("access_token").asText();
        accessTokenExpiresAt = now.plusSeconds(body.path("expires_in").asLong(3600));
        return accessToken;
    }

    private static PrivateKey privateKey(String pem) throws Exception {
        String base64 = pem.replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replaceAll("\\s", "");
        return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64)));
    }

    private static String abbreviate(String s) {
        return s == null ? "" : s.length() > 300 ? s.substring(0, 300) + "…" : s;
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
