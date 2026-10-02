package com.crm.dashboard;

import com.crm.dashboard.DirectorDashboardService.Section;
import com.crm.entity.DirectorDigestLog;
import com.crm.repository.DirectorDigestLogRepository;
import com.crm.service.TelegramService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;

/**
 * Kunlik Telegram xulosasi direktorga (director-dashboard §5, §7 #17): 20:00 (Asia/Tashkent),
 * dashboard sarlavhasi bilan AYNAN bir xil raqamlar. Default o'chiq
 * ({@code app.dashboard.digest.enabled}); chat id lar faqat env'dan. Shaxsiy ma'lumot
 * (ism, telefon) yuborilmaydi. Bir kunga bir chat'ga bitta muvaffaqiyatli xabar
 * ({@code director_digest_log}) — ilova qayta ishga tushsa ham takrorlanmaydi.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DirectorDigestService {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    private final DashboardProperties properties;
    private final DirectorDashboardService dashboardService;
    private final DirectorSnapshotService snapshotService;
    private final TelegramService telegramService;
    private final DirectorDigestLogRepository logRepository;
    private final Clock billingClock;

    public record Result(boolean enabled, String text, int sent, int skipped, int failed) {
    }

    @Scheduled(cron = "${app.dashboard.digest.cron:0 0 20 * * *}", zone = "Asia/Tashkent")
    public void scheduled() {
        if (!properties.getDigest().isEnabled()) {
            return;
        }
        try {
            send(LocalDate.now(billingClock));
        } catch (RuntimeException e) {
            log.error("Direktor xulosasi yuborilmadi: {}", e.getMessage(), e);
        }
    }

    /** 20:00 snapshot (final=false) + matn + yuborish. O'chiq bo'lsa hech narsa qilmaydi. */
    public Result send(LocalDate day) {
        if (!properties.getDigest().isEnabled()) {
            return new Result(false, null, 0, 0, 0);
        }
        snapshotService.snapshotDay(day, false);
        String text = text(day);
        int sent = 0;
        int skipped = 0;
        int failed = 0;
        for (String chatId : chatIds()) {
            if (logRepository.existsByStatDateAndChatIdAndOkTrue(day, chatId)) {
                skipped++;
                continue;
            }
            boolean ok;
            String error = null;
            try {
                ok = telegramService.sendMessage(chatId, text);
                if (!ok) {
                    error = "TelegramService false qaytardi";
                }
            } catch (RuntimeException e) {
                ok = false;
                error = e.getMessage();
            }
            DirectorDigestLog row = logRepository.findByStatDateAndChatId(day, chatId)
                .orElseGet(() -> DirectorDigestLog.builder().statDate(day).chatId(chatId).build());
            row.setSentAt(LocalDateTime.now(billingClock));
            row.setOk(ok);
            row.setError(error);
            logRepository.save(row);
            if (ok) {
                sent++;
            } else {
                failed++;
                log.warn("Direktor xulosasi chat={} ga yuborilmadi: {}", chatId, error);
            }
        }
        return new Result(true, text, sent, skipped, failed);
    }

    /** Xabar matni — §5 namunasi; raqamlar {@link DirectorDashboardService#headline} dan. */
    public String text(LocalDate day) {
        LocalDateTime now = LocalDateTime.now(billingClock);
        DashboardPeriod p = DashboardPeriod.of("DAY", day, null, null, now);
        DirectorDashboardService.Summary s = dashboardService.computeLive(p,
            new DirectorDashboardService.Params("DAY", day, null, null, null, null, false),
            EnumSet.of(Section.FUNNEL, Section.COLLECTIONS, Section.DEBTORS, Section.ATTENDANCE, Section.TRIALS,
                Section.OPERATORS));
        List<String> lines = new ArrayList<>();
        lines.add("📊 Adizone — " + day.format(DAY) + " (" + now.format(TIME) + " holati)");
        // Har bo'lim o'z sarlavhasi bilan — dashboard matni bilan aynan bir xil raqamlar
        line(lines, "Lidlar: ", DirectorDashboardService.headline(s.funnel(), null, null, null, null, null));
        line(lines, "To'lovlar: ", DirectorDashboardService.headline(null, s.collections(), null, null, null, null));
        line(lines, "Qarzdorlar: ", DirectorDashboardService.headline(null, null, s.debtors(), null, null, null));
        line(lines, "Davomat: ", DirectorDashboardService.headline(null, null, null, s.attendance(), null, null));
        line(lines, "", DirectorDashboardService.headline(null, null, null, null, s.trials(), null));
        line(lines, "", DirectorDashboardService.headline(null, null, null, null, null, s.operators()));
        String url = properties.getDigest().getDashboardUrl();
        if (url != null && !url.isBlank()) {
            lines.add("👉 " + url);
        }
        return String.join("\n", lines);
    }

    private static void line(List<String> lines, String label, List<String> one) {
        one.forEach(l -> lines.add(label + l));
    }

    private List<String> chatIds() {
        String raw = properties.getDigest().getChatIds();
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return Arrays.stream(raw.split(",")).map(String::trim).filter(x -> !x.isEmpty()).distinct().toList();
    }
}
