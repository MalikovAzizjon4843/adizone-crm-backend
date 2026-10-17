package com.crm.service;

import com.crm.billing.BillingStatusService;
import com.crm.billing.DebtorService;
import com.crm.dto.response.ExpectedPaymentsResponse;
import com.crm.notification.DispatchResult;
import com.crm.notification.NotificationRequest;
import com.crm.notification.NotificationService;
import com.crm.notification.NotificationType;
import com.crm.notification.NotifyProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * "To'lov yaqinlashdi" (docs/design/notifications.md §4) — har kuni ({@code app.notify.payment-due-soon.cron},
 * standart 10:00): keyingi to'lov sanasi bugun + N kun ({@code days-before}, standart 3) bo'lgan o'quvchilar.
 *
 * <p>Ro'yxat va summa — "kutilayotgan to'lovlar" bilan AYNAN bir xil ({@link DebtorService#expected}, R2):
 * oldindan to'langan davrlar sanani suradi, oxirgi davr darslar bo'yicha bo'lsa — o'sha summa. Summa 0 — yo'q.
 * Bir o'quvchida bir nechta guruh shu sanaga tushsa — bitta xabar, summalar yig'indisi.
 * Takror yo'q: {@code due:{studentId}:{sana}}. Flag {@code app.notify.payment-due-soon.enabled} — dispatch'da.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PaymentDueSoonJob {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private final DebtorService debtorService;
    private final BillingStatusService billingStatusService;
    private final NotificationService notificationService;
    private final NotifyProperties properties;

    /** Natija: o'quvchilar, navbatga qo'yilgan, takror, ulanmagan, flag o'chiq, o'zi o'chirgan, xato. */
    public record Summary(int students, int queued, int duplicates, int unlinked, int disabled, int optedOut,
                          int failed) {
    }

    @Scheduled(cron = "${app.notify.payment-due-soon.cron:0 0 10 * * *}", zone = "Asia/Tashkent")
    public void scheduled() {
        try {
            Summary s = run(billingStatusService.today());
            log.info("To'lov yaqinlashdi: {} o'quvchi, {} navbatda, {} takror, {} ulanmagan, {} o'chiq (flag), "
                    + "{} o'chirgan, {} xato", s.students(), s.queued(), s.duplicates(), s.unlinked(), s.disabled(),
                s.optedOut(), s.failed());
        } catch (RuntimeException e) {
            log.error("To'lov yaqinlashdi eslatmasi yiqildi: {}", e.getMessage(), e);
        }
    }

    /** Bitta aylanish (testlanadi). Har o'quvchi — o'z tranzaksiyasida (dispatch). */
    public Summary run(LocalDate today) {
        LocalDate due = today.plusDays(Math.max(1, properties.getPaymentDueSoon().getDaysBefore()));
        Map<Long, List<ExpectedPaymentsResponse.ExpectedStudent>> byStudent = new LinkedHashMap<>();
        for (ExpectedPaymentsResponse.DayBucket day : debtorService.expected(due, due, today).getDays()) {
            for (ExpectedPaymentsResponse.ExpectedStudent s : day.getStudents()) {
                if (s.getAmount() != null && s.getAmount().signum() > 0) {
                    byStudent.computeIfAbsent(s.getStudentId(), k -> new ArrayList<>()).add(s);
                }
            }
        }
        DispatchResult total = DispatchResult.EMPTY;
        int failed = 0;
        for (Map.Entry<Long, List<ExpectedPaymentsResponse.ExpectedStudent>> e : byStudent.entrySet()) {
            try {
                total = total.plus(send(e.getKey(), e.getValue(), due));
            } catch (RuntimeException ex) {
                failed++;
                log.error("To'lov yaqinlashdi eslatmasi yuborilmadi (student={})", e.getKey(), ex);
            }
        }
        return new Summary(byStudent.size(), total.telegram(), total.duplicates(), total.unlinked(), total.disabled(),
            total.optedOut(), failed);
    }

    private DispatchResult send(Long studentId, List<ExpectedPaymentsResponse.ExpectedStudent> rows, LocalDate due) {
        BigDecimal amount = rows.stream().map(ExpectedPaymentsResponse.ExpectedStudent::getAmount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        String groups = rows.stream().map(ExpectedPaymentsResponse.ExpectedStudent::getGroupName)
            .filter(g -> g != null && !g.isBlank()).distinct().collect(Collectors.joining(", "));
        // Oxirgi davr darslar bo'yicha (V81) — summa o'sha davrniki, xabarda dars soni ham
        Integer lessons = rows.size() == 1 ? rows.get(0).getProratedLessons() : null;
        NotificationRequest.NotificationRequestBuilder b = NotificationRequest.builder()
            .type(NotificationType.PAYMENT_DUE_SOON)
            .variant(lessons != null ? "lessons" : null)
            .studentId(studentId)
            .arg(rows.get(0).getFullName())
            .arg(groups)
            .arg(due.format(DAY))
            .arg(PaymentReminderService.money(amount))
            .dedupeKey("due:" + studentId + ":" + due)
            .appPath("/#/payments");
        if (lessons != null) {
            b.arg(String.valueOf(lessons));
        }
        return notificationService.dispatch(b.build());
    }
}
