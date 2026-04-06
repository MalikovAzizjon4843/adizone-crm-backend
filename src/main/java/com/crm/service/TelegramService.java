package com.crm.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;

@Service
@Slf4j
public class TelegramService {

    @Value("${telegram.bot-token:}")
    private String botToken;

    @Value("${telegram.enabled:false}")
    private boolean enabled;

    private final RestTemplate restTemplate = new RestTemplate();

    public boolean sendMessage(String chatId, String message) {
        if (!enabled || botToken.isBlank()
            || chatId == null || chatId.isBlank()) {
            log.debug("Telegram disabled or no chatId");
            return false;
        }

        try {
            String url = "https://api.telegram.org/bot"
                + botToken + "/sendMessage";

            Map<String, Object> body = new HashMap<>();
            body.put("chat_id", chatId);
            body.put("text", message);
            body.put("parse_mode", "HTML");

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            HttpEntity<Map<String, Object>> request =
                new HttpEntity<>(body, headers);

            ResponseEntity<String> response =
                restTemplate.postForEntity(url, request, String.class);

            return response.getStatusCode().is2xxSuccessful();
        } catch (Exception e) {
            log.error("Telegram xabar yuborishda xatolik: {}",
                e.getMessage());
            return false;
        }
    }

    public void sendPaymentReminder(String parentPhone,
            String parentName, String studentName,
            String groupName, int daysOverdue,
            double amount) {
        log.info("📱 To'lov eslatmasi: {} → {} uchun "
                + "{} kun kechikkan, {} UZS",
            parentName, studentName, daysOverdue, amount);
    }

    public String buildAttendanceMessage(String studentName,
            String groupName, String date) {
        return String.format(
            "📚 <b>Davomat xabari</b>%n%n"
                + "Hurmatli ota-ona!%n%n"
                + "👤 <b>O'quvchi:</b> %s%n"
                + "📚 <b>Guruh:</b> %s%n"
                + "📅 <b>Sana:</b> %s%n%n"
                + "❌ O'quvchingiz bugun darsga <b>kelmadi</b>.%n%n"
                + "📞 Murojaat: Adizone o'quv markazi",
            studentName, groupName, date
        );
    }

    public String buildPaymentMessage(String studentName,
            String groupName, int daysOverdue, double amount) {
        return String.format(
            "💰 <b>To'lov eslatmasi</b>%n%n"
                + "Hurmatli ota-ona!%n%n"
                + "👤 <b>O'quvchi:</b> %s%n"
                + "📚 <b>Guruh:</b> %s%n"
                + "⏰ <b>Kechikish:</b> %d kun%n"
                + "💵 <b>Summa:</b> %,.0f UZS%n%n"
                + "Iltimos, to'lovni amalga oshiring.%n"
                + "📞 Murojaat: Adizone o'quv markazi",
            studentName, groupName, daysOverdue, amount
        );
    }
}
