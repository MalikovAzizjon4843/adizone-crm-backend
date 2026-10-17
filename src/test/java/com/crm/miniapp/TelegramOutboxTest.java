package com.crm.miniapp;

import com.crm.entity.TelegramOutbox;
import com.crm.telegram.MessageOrigin;
import com.crm.telegram.TelegramOutboxService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;

/** Outbox: sokin soatlar, dedupe, qayta urinish (docs/design/telegram-platform.md §11.5). */
class TelegramOutboxTest extends MiniAppItBase {

    @Autowired
    private TelegramOutboxService outboxService;

    @Test
    void quietHoursBoundaries() {
        assertThat(TelegramOutboxService.isQuiet(LocalTime.of(20, 59))).isFalse();
        assertThat(TelegramOutboxService.isQuiet(LocalTime.of(21, 0))).isTrue();
        assertThat(TelegramOutboxService.isQuiet(LocalTime.of(3, 0))).isTrue();
        assertThat(TelegramOutboxService.isQuiet(LocalTime.of(7, 59))).isTrue();
        assertThat(TelegramOutboxService.isQuiet(LocalTime.of(8, 0))).isFalse();
    }

    /** Buyurtmachi 17.10.2026: avtomatik — tunda hech qaysi priority bilan emas, 08:00 da (ovoz bilan). */
    @Test
    void quietHours_automaticWaitsUntil0800_evenHigh_withSound() {
        clock.setDateTime(LocalDateTime.of(2026, 9, 15, 22, 30));
        inTx(() -> {
            outboxService.enqueue(1511, "high", null, TelegramOutbox.Priority.HIGH, "q:1", "TEST",
                MessageOrigin.AUTOMATIC);
            outboxService.enqueue(1511, "normal", null, TelegramOutbox.Priority.NORMAL, "q:2", "TEST",
                MessageOrigin.AUTOMATIC);
        });
        assertThat(outboxFor(1511)).allSatisfy(o -> {
            assertThat(o.getNotBefore()).isEqualTo(LocalDateTime.of(2026, 9, 16, 8, 0));
            assertThat(o.getSilent()).isFalse();
        });
        assertThat(outboxWorker.runOnce()).isZero();

        clock.setDateTime(LocalDateTime.of(2026, 9, 16, 7, 59));
        inTx(() -> outboxService.enqueue(1511, "tong", null, TelegramOutbox.Priority.HIGH, "q:3", "TEST",
            MessageOrigin.AUTOMATIC));
        assertThat(outboxFor(1511).get(2).getNotBefore()).as("07:59 — bugun 08:00")
            .isEqualTo(LocalDateTime.of(2026, 9, 16, 8, 0));
        clock.setDateTime(LocalDateTime.of(2026, 9, 16, 8, 0));
        assertThat(outboxWorker.runOnce()).isEqualTo(3);
        assertThat(botApi.sent).allSatisfy(s -> assertThat(s.silent()).isFalse());
    }

    /** Qo'lda boshlangan (chat, e'lon) — tunda ham darhol, ovozsiz; kunduzi — ovoz bilan. */
    @Test
    void quietHours_manualImmediate_silentAtNightOnly() {
        clock.setDateTime(LocalDateTime.of(2026, 9, 15, 23, 0));
        inTx(() -> outboxService.enqueue(1512, "tunda", null, TelegramOutbox.Priority.NORMAL, "m:1", "TEST",
            MessageOrigin.MANUAL));
        assertThat(outboxFor(1512)).singleElement().satisfies(o -> {
            assertThat(o.getNotBefore()).isEqualTo(LocalDateTime.of(2026, 9, 15, 23, 0));
            assertThat(o.getSilent()).isTrue();
        });
        assertThat(outboxWorker.runOnce()).isEqualTo(1);

        clock.setDateTime(LocalDateTime.of(2026, 9, 16, 12, 0));
        inTx(() -> outboxService.enqueue(1512, "kunduzi", null, TelegramOutbox.Priority.NORMAL, "m:2", "TEST",
            MessageOrigin.MANUAL));
        assertThat(outboxFor(1512).get(1).getSilent()).isFalse();
    }

    @Test
    void dedupeKey_secondInsertIgnored_withoutBreakingTransaction() {
        boolean[] results = inTx(() -> new boolean[]{
            outboxService.enqueue(1501, "a", null, TelegramOutbox.Priority.NORMAL, "k:1", "TEST",
                MessageOrigin.AUTOMATIC),
            outboxService.enqueue(1501, "b", null, TelegramOutbox.Priority.NORMAL, "k:1", "TEST",
                MessageOrigin.AUTOMATIC),
            outboxService.enqueue(1501, "c", null, TelegramOutbox.Priority.NORMAL, null, "TEST",
                MessageOrigin.AUTOMATIC)});
        assertThat(results).containsExactly(true, false, true);
        assertThat(outboxFor(1501)).extracting(TelegramOutbox::getText).containsExactly("a", "c");
    }

    @Test
    void failure_backoffThenFailed() {
        inTx(() -> outboxService.enqueue(1502, "salom", null, TelegramOutbox.Priority.NORMAL, "k:2", "TEST",
            MessageOrigin.AUTOMATIC));
        botApi.sendOk = false;

        LocalDateTime t = LocalDateTime.of(2026, 9, 15, 12, 0);
        int[] backoff = {1, 5, 30, 120};
        for (int attempt = 1; attempt <= 4; attempt++) {
            assertThat(outboxWorker.runOnce()).isZero();
            TelegramOutbox row = outboxFor(1502).get(0);
            assertThat(row.getAttempts()).isEqualTo(attempt);
            assertThat(row.getStatus()).isEqualTo(TelegramOutbox.Status.PENDING);
            assertThat(row.getNotBefore()).isEqualTo(t.plusMinutes(backoff[attempt - 1]));
            // Muddatidan oldin qayta yuborilmaydi
            assertThat(outboxWorker.runOnce()).isZero();
            assertThat(outboxFor(1502).get(0).getAttempts()).isEqualTo(attempt);
            t = row.getNotBefore();
            clock.setDateTime(t);
        }
        outboxWorker.runOnce();
        assertThat(outboxFor(1502).get(0).getStatus()).isEqualTo(TelegramOutbox.Status.FAILED);
        assertThat(botApi.sent).hasSize(5);
    }
}
