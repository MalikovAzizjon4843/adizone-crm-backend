package com.crm.dashboard;

import com.crm.billing.support.AbstractBillingIT;
import com.crm.dto.request.LeadCreateRequest;
import com.crm.entity.DirectorDailyStat;
import com.crm.entity.enums.UserRole;
import com.crm.repository.DirectorDailyStatRepository;
import com.crm.repository.DirectorDigestLogRepository;
import com.crm.service.LeadService;
import com.crm.entity.TelegramOutbox;
import com.crm.repository.TelegramOutboxRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Direktor dashboardi, 5-bosqich: kunlik snapshot (§3.6, §7 #19) va Telegram xulosasi (§5). */
class DirectorSnapshotDigestTest extends AbstractBillingIT {

    @Autowired DirectorSnapshotService snapshots;
    @Autowired DirectorDigestService digest;
    @Autowired DirectorDailyStatRepository statRepo;
    @Autowired DirectorDigestLogRepository logRepo;
    @Autowired DashboardProperties properties;
    @Autowired LeadService leadService;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper objectMapper;
    @Autowired TelegramOutboxRepository outboxRepo;

    @AfterEach
    void resetDigest() {
        properties.getDigest().setEnabled(false);
        properties.getDigest().setChatIds("");
        properties.getDigest().setDashboardUrl("");
    }

    private void leadAt(LocalDateTime at) {
        fixtures.loginAs(UserRole.SUPER_ADMIN);
        LeadCreateRequest r = new LeadCreateRequest();
        r.setFullName("Ali Valiyev");
        r.setPhone("+998901234567");
        Long id = leadService.createLeadByStaff(r).getId();
        jdbc.update("UPDATE leads SET created_at = ? WHERE id = ?", at, id);
    }

    private DirectorDailyStat stat(String day, String section) {
        return inTx(() -> statRepo.findById(new DirectorDailyStat.Key(d(day), section)).orElse(null));
    }

    private long leadsCreated(DirectorDailyStat st) throws Exception {
        return objectMapper.readTree(st.getPayload()).at("/activity/leadsCreated").asLong();
    }

    @Test
    void snapshotDay_writesSections_versionsAndFinalFlag() throws Exception {
        leadAt(LocalDateTime.of(2026, 10, 1, 10, 0));
        clock.setDateTime(LocalDateTime.of(2026, 10, 1, 20, 0));

        Map<String, Integer> first = snapshots.snapshotDay(d("01.10.2026"), false);
        assertThat(first.keySet()).containsExactly("funnel", "collections", "attendance", "trials", "operators", "debtors");
        assertThat(stat("01.10.2026", "funnel").isFinalized()).isFalse();
        assertThat(leadsCreated(stat("01.10.2026", "funnel"))).isEqualTo(1);
        assertThat(objectMapper.readTree(stat("01.10.2026", "debtors").getPayload()).has("studentIds")).isTrue();

        clock.setDateTime(LocalDateTime.of(2026, 10, 1, 23, 55));
        snapshots.snapshotDay(d("01.10.2026"), true);
        DirectorDailyStat fin = stat("01.10.2026", "funnel");
        assertThat(fin.isFinalized()).isTrue();
        assertThat(fin.getVersion()).isEqualTo(2);
        assertThat(fin.getComputedAt()).isEqualTo(LocalDateTime.of(2026, 10, 1, 23, 55));
    }

    @Test
    void nightly_recomputesLast7Days_lateEditsReflected_debtorsFrozen() throws Exception {
        leadAt(LocalDateTime.of(2026, 10, 1, 10, 0));
        clock.setDateTime(LocalDateTime.of(2026, 10, 1, 23, 55));
        snapshots.nightly();
        int debtorsVersion = stat("01.10.2026", "debtors").getVersion();

        // 02.10 kechasi: 01.10 ga orqaga sanalangan lid kiritildi
        leadAt(LocalDateTime.of(2026, 10, 1, 15, 0));
        clock.setDateTime(LocalDateTime.of(2026, 10, 2, 23, 55));
        snapshots.nightly();

        DirectorDailyStat d1 = stat("01.10.2026", "funnel");
        assertThat(leadsCreated(d1)).isEqualTo(2);
        assertThat(d1.getVersion()).isEqualTo(2);
        assertThat(d1.isFinalized()).isTrue();
        assertThat(stat("01.10.2026", "debtors").getVersion()).as("qarzdorlar tarixi qayta yozilmaydi")
            .isEqualTo(debtorsVersion);
        assertThat(stat("02.10.2026", "debtors")).isNotNull();
        assertThat(stat("24.09.2026", "funnel")).as("01.10 kechasi: 7 kun oynasi 24.09 gacha").isNotNull();
        assertThat(stat("23.09.2026", "funnel")).isNull();
    }

    @Test
    void digest_disabledByDefault_sendsNothing() {
        clock.setDateTime(LocalDateTime.of(2026, 10, 1, 20, 0));
        DirectorDigestService.Result r = digest.send(d("01.10.2026"));
        assertThat(r.enabled()).isFalse();
        assertThat(inTx(() -> outboxRepo.count())).isZero();
        assertThat(stat("01.10.2026", "funnel")).isNull();
    }

    /** Bot outbox orqali (notifications.md): kun + chat bo'yicha bitta, noto'g'ri chat id — xato, shaxsiy ma'lumot yo'q. */
    @Test
    void digest_queuedOncePerChat_viaOutbox_noPersonalData() {
        leadAt(LocalDateTime.of(2026, 10, 1, 10, 0));
        clock.setDateTime(LocalDateTime.of(2026, 10, 1, 20, 0));
        properties.getDigest().setEnabled(true);
        properties.getDigest().setChatIds("111, 222, abc");
        properties.getDigest().setDashboardUrl("https://admin.adizone.uz/dashboard");

        DirectorDigestService.Result first = digest.send(d("01.10.2026"));

        assertThat(first.sent()).isEqualTo(2);
        assertThat(first.failed()).as("abc — chat id emas").isEqualTo(1);
        assertThat(first.text()).startsWith("📊 Adizone — 01.10.2026 (20:00 holati)")
            .contains("Lidlar: 1 lid → 0 tashrif → 0 birinchi to'lov")
            .contains("To'lovlar: 0 muddati kelgan → 0 undirildi")
            .contains("Davomat: 0 darsdan 0 tasida davomat qilindi")
            .contains("👉 https://admin.adizone.uz/dashboard")
            .doesNotContain("Ali").doesNotContain("+99890");
        assertThat(stat("01.10.2026", "funnel").isFinalized()).isFalse();
        TelegramOutbox row = inTx(() -> outboxRepo.findByChatIdOrderByIdAsc(111L)).get(0);
        assertThat(row.getEventCode()).isEqualTo("DIRECTOR_DIGEST");
        assertThat(row.getDedupeKey()).isEqualTo("digest:2026-10-01:c111");
        assertThat(row.getText()).isEqualTo(first.text());

        DirectorDigestService.Result second = digest.send(d("01.10.2026"));
        assertThat(second.skipped()).as("111, 222 — allaqachon navbatda").isEqualTo(2);
        assertThat(second.sent()).isZero();
        assertThat(inTx(() -> outboxRepo.findByChatIdOrderByIdAsc(111L))).hasSize(1);
        assertThat(inTx(() -> outboxRepo.findByChatIdOrderByIdAsc(222L))).hasSize(1);
        assertThat(inTx(() -> logRepo.count())).isEqualTo(3);
    }
}
