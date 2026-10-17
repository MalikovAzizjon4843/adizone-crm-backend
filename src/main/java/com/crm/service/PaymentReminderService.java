package com.crm.service;

import com.crm.billing.BillingStatusService;
import com.crm.billing.DebtorService;
import com.crm.dto.response.DebtorsListResponse;
import com.crm.entity.enums.StudentStatus;
import com.crm.notification.DispatchResult;
import com.crm.notification.NotificationRequest;
import com.crm.notification.NotificationService;
import com.crm.notification.NotificationType;
import com.crm.repository.StudentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

/**
 * Telegram qarz eslatmasi — billing v2 (docs/design/billing-v2.md §4.5, §13 #5, #26), bot orqali
 * ({@link NotificationService#dispatch}, docs/design/notifications.md).
 *
 * <ul>
 *   <li>Ro'yxat — yagona {@link DebtorService} dan (faqat OVERDUE).</li>
 *   <li>Summa — haqiqiy QARZ (avval oylik narx edi).</li>
 *   <li>OVERDUE bo'lgan kuni va keyin har 3 kunda (har kuni emas).</li>
 *   <li>Muzlatilgan o'quvchilarga yuborilmaydi.</li>
 *   <li>Qabul qiluvchi — o'quvchiga ulangan ota-ona va o'quvchining o'zi ({@code AppIdentityStudent}); ulanmagan —
 *       jim o'tkaziladi va {@link Summary#unlinked()} da sanaladi.</li>
 *   <li>Bir o'quvchiga bir kunda bir marta: {@code pay:{studentId}:{sana}} (job qayta ishga tushsa ham).</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentReminderService {

    /** OVERDUE boshlangan kundan keyin shuncha kunda bir marta. */
    static final int REPEAT_EVERY_DAYS = 3;

    private final DebtorService debtorService;
    private final BillingStatusService billingStatusService;
    private final StudentRepository studentRepository;
    private final NotificationService notificationService;

    /**
     * Bir aylanish natijasi: qarzdorlar, navbatga qo'yilgan xabarlar, takrorlar, ulanmagan o'quvchilar, flag o'chiq
     * (app.notify.payment-overdue.enabled=false) sababli o'tkazilganlar, xatolar.
     */
    public record Summary(int students, int queued, int duplicates, int unlinked, int disabled, int failed) {
    }

    @Scheduled(cron = "${app.billing.reminder-cron:0 0 10 * * *}", zone = "Asia/Tashkent")
    public void sendPaymentReminders() {
        try {
            Summary s = run(billingStatusService.today());
            log.info("To'lov eslatmalari: {} o'quvchi, {} xabar navbatda, {} takror, {} ulanmagan, {} o'chiq (flag), {} xato",
                s.students(), s.queued(), s.duplicates(), s.unlinked(), s.disabled(), s.failed());
        } catch (Exception e) {
            log.error("Payment reminder xatolik", e);
        }
    }

    /** Bugungi eslatmalar (testlanadi). Har o'quvchi — o'z tranzaksiyasida (dispatch): biri yiqilsa boshqasi ketadi. */
    public Summary run(LocalDate today) {
        int queued = 0;
        int duplicates = 0;
        int unlinked = 0;
        int disabled = 0;
        int failed = 0;
        List<DebtorsListResponse.DebtorStudent> due = dueToday(today);
        for (DebtorsListResponse.DebtorStudent d : due) {
            try {
                DispatchResult r = send(d, today);
                queued += r.telegram();
                duplicates += r.duplicates();
                unlinked += r.unlinked();
                disabled += r.disabled();
            } catch (Exception e) {
                failed++;
                log.error("Eslatma yuborishda xatolik (student={})", d.getStudentId(), e);
            }
        }
        return new Summary(due.size(), queued, duplicates, unlinked, disabled, failed);
    }

    /** Bugun eslatma oladiganlar (testlanadi). */
    List<DebtorsListResponse.DebtorStudent> dueToday(LocalDate today) {
        // Qarzdor bo'lgan kun (today − debtSince = grace, R1) va keyin har 3 kunda (§13 #26)
        int firstOverdueDay = billingStatusService.graceDays();
        return debtorService.debtors(DebtorService.Filter.defaults(), today).getStudents().stream()
            .filter(d -> studentRepository.findById(d.getStudentId())
                .map(s -> s.getStatus() != StudentStatus.FROZEN).orElse(false))
            .filter(d -> d.getDaysOverdue() >= firstOverdueDay
                && (d.getDaysOverdue() - firstOverdueDay) % REPEAT_EVERY_DAYS == 0)
            .toList();
    }

    private DispatchResult send(DebtorsListResponse.DebtorStudent d, LocalDate today) {
        return notificationService.dispatch(NotificationRequest.builder()
            .type(NotificationType.PAYMENT_OVERDUE)
            .studentId(d.getStudentId())
            .arg(d.getFullName())
            .arg(d.getGroupName() != null ? d.getGroupName() : "")
            .arg(String.valueOf(d.getDaysOverdue()))
            .arg(money(d.getDebt()))
            .dedupeKey(payKey(d.getStudentId(), today))
            .appPath("/#/payments")
            .build());
    }

    static String payKey(Long studentId, LocalDate day) {
        return "pay:" + studentId + ":" + day;
    }

    /** {@code 630000} → {@code 630 000} (lokalga bog'liq emas). */
    static String money(BigDecimal amount) {
        return amount == null ? "0" : String.format(Locale.ROOT, "%,.0f", amount).replace(',', ' ');
    }
}
