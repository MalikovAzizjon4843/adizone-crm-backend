package com.crm.telegram;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Bot API javobini o'qish (outbox qarorlari uchun): error_code, description (tokensiz), retry_after. */
class HttpTelegramBotApiTest {

    private static final String TOKEN = "123456:TEST-token-value";
    private final ObjectMapper json = new ObjectMapper();

    private HttpTelegramBotApi api() {
        TelegramProperties p = new TelegramProperties();
        p.setBotToken(TOKEN);
        return new HttpTelegramBotApi(p, json);
    }

    @Test
    void ok() throws Exception {
        assertThat(api().result(json.readTree("{\"ok\":true,\"result\":{}}")).ok()).isTrue();
    }

    @Test
    void blocked403() throws Exception {
        SendResult r = api().result(json.readTree(
            "{\"ok\":false,\"error_code\":403,\"description\":\"Forbidden: bot was blocked by the user\"}"));
        assertThat(r.blocked()).isTrue();
        assertThat(r.permanent()).isTrue();
        assertThat(r.describe()).isEqualTo("HTTP 403: Forbidden: bot was blocked by the user");
    }

    @Test
    void rateLimited429_retryAfter() throws Exception {
        SendResult r = api().result(json.readTree("{\"ok\":false,\"error_code\":429,"
            + "\"description\":\"Too Many Requests: retry after 12\",\"parameters\":{\"retry_after\":12}}"));
        assertThat(r.rateLimited()).isTrue();
        assertThat(r.permanent()).isFalse();
        assertThat(r.retryAfter()).isEqualTo(12);
    }

    @Test
    void tokenMaskedInDescription() throws Exception {
        SendResult r = api().result(json.readTree(
            "{\"ok\":false,\"error_code\":400,\"description\":\"bad url /bot" + TOKEN + "/sendMessage\"}"));
        assertThat(r.describe()).doesNotContain(TOKEN).contains("/bot***/sendMessage");
    }

    @Test
    void serverError_isRetryable() throws Exception {
        SendResult r = api().result(json.readTree("{\"ok\":false,\"error_code\":502,\"description\":\"Bad Gateway\"}"));
        assertThat(r.permanent()).isFalse();
        assertThat(r.blocked()).isFalse();
    }
}
