package com.crm.service;

import com.crm.dto.response.DebtorResponse;
import com.crm.entity.Parent;
import com.crm.repository.ParentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentReminderService {

    private final PaymentService paymentService;
    private final ParentRepository parentRepository;
    private final TelegramService telegramService;

    @Scheduled(cron = "0 0 10 * * *")
    public void sendPaymentReminders() {
        log.info("To'lov eslatmalari yuborilmoqda...");

        try {
            List<DebtorResponse> debtors = paymentService.getDebtors();

            debtors.forEach(d -> {
                try {
                    if (d.getDaysOverdue() < 3) {
                        return;
                    }

                    List<Parent> parents =
                        parentRepository.findByStudentId(d.getStudentId());

                    String message = telegramService.buildPaymentMessage(
                        d.getStudentName(),
                        d.getGroupName(),
                        (int) d.getDaysOverdue(),
                        d.getMonthlyAmount().doubleValue());

                    for (Parent parent : parents) {
                        if (parent.getTelegramChatId() != null
                            && !parent.getTelegramChatId().isBlank()) {
                            telegramService.sendMessage(
                                parent.getTelegramChatId(), message);
                        } else if (parent.getPhone() != null) {
                            log.info("💰 Eslatma: {} → {} uchun",
                                parent.getFullName(), d.getStudentName());
                        }
                    }
                } catch (Exception e) {
                    log.error("Eslatma yuborishda xatolik", e);
                }
            });
        } catch (Exception e) {
            log.error("Payment reminder xatolik", e);
        }
    }
}
