package com.crm.service;

import com.crm.billing.BillingStatusService;
import com.crm.billing.DebtorService;
import com.crm.dto.response.DebtorsListResponse;
import com.crm.entity.Parent;
import com.crm.entity.enums.StudentStatus;
import com.crm.repository.ParentRepository;
import com.crm.repository.StudentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

/**
 * Telegram qarz eslatmasi — billing v2 (docs/design/billing-v2.md §4.5, §13 #5, #26).
 *
 * <ul>
 *   <li>Ro'yxat — yagona {@link DebtorService} dan (faqat OVERDUE).</li>
 *   <li>Summa — haqiqiy QARZ (avval oylik narx edi).</li>
 *   <li>OVERDUE bo'lgan kuni va keyin har 3 kunda (har kuni emas).</li>
 *   <li>Muzlatilgan o'quvchilarga yuborilmaydi.</li>
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
    private final ParentRepository parentRepository;
    private final TelegramService telegramService;

    @Scheduled(cron = "${app.billing.reminder-cron:0 0 10 * * *}", zone = "Asia/Tashkent")
    public void sendPaymentReminders() {
        log.info("To'lov eslatmalari yuborilmoqda...");
        try {
            LocalDate today = billingStatusService.today();
            for (DebtorsListResponse.DebtorStudent d : dueToday(today)) {
                try {
                    send(d);
                } catch (Exception e) {
                    log.error("Eslatma yuborishda xatolik (student={})", d.getStudentId(), e);
                }
            }
        } catch (Exception e) {
            log.error("Payment reminder xatolik", e);
        }
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

    private void send(DebtorsListResponse.DebtorStudent d) {
        List<Parent> parents = parentRepository.findByStudentId(d.getStudentId());
        String message = telegramService.buildPaymentMessage(
            d.getFullName(), d.getGroupName(), (int) d.getDaysOverdue(), d.getDebt());
        for (Parent parent : parents) {
            if (parent.getTelegramChatId() != null && !parent.getTelegramChatId().isBlank()) {
                telegramService.sendMessage(parent.getTelegramChatId(), message);
            } else if (parent.getPhone() != null) {
                log.info("Eslatma: {} → {} uchun", parent.getFullName(), d.getFullName());
            }
        }
    }
}
