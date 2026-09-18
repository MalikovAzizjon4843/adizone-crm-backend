package com.crm.service;

import com.crm.entity.MetaWebhookEvent;
import com.crm.repository.MetaWebhookEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Bitta webhook eventining bazadagi holatini ALOHIDA tranzaksiyalarda
 * boshqaradi.
 *
 * <p><b>Nega alohida bean.</b> {@code REQUIRES_NEW} faqat Spring proxysi
 * orqali chaqirilganda ishlaydi — {@link MetaLeadProcessingService} ichida
 * o'z-o'ziga chaqiruv tranzaksiyani ochmaydi va bitta buzuq lid butun
 * partiyani yiqitardi. Bu {@code MonthlyLedgerRepairWorker} dagi bilan
 * bir xil naqsh.
 *
 * <p><b>Nega uch metod.</b> PostgreSQL da tranzaksiya ichidagi bitta xato
 * seansni {@code 25P02} ("current transaction is aborted") holatiga
 * tushiradi va undan keyingi HAR QANDAY so'rov rad etiladi. Ya'ni
 * "urinib ko'r, xato bo'lsa shu yerda xatoni yozib qo'y" ishlamaydi:
 * xatoni yozish uchun YANGI, toza tranzaksiya kerak. Shuning uchun urinish
 * boshlash, muvaffaqiyat va muvaffaqiyatsizlik — uchtasi ham mustaqil.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MetaEventWorker {

    private final MetaWebhookEventRepository eventRepository;
    private final MetaLeadIngestService ingestService;

    /** Graph so'roviga kerak bo'ladigan minimum — entity tranzaksiyadan chiqmaydi. */
    public record EventSnapshot(Long id, String leadgenId, String formId, int attempts) {
    }

    /**
     * Webhook qatorini yozadi. Takrori bo'lsa jimgina false qaytaradi.
     *
     * <p>Ikki qatlamli himoya: avval {@code exists} tekshiruvi (odatiy
     * holat, arzon), keyin UNIQUE indeks buzilishini ushlash (ikki xabar
     * bir vaqtda kelgan poyga holati). Har qator O'Z tranzaksiyasida —
     * bittasining dublikati qolganlarini yiqitmasin.
     *
     * @return true — yangi qator yozildi
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean saveIfNew(MetaWebhookEvent event) {
        if (eventRepository.existsByLeadgenId(event.getLeadgenId())) {
            return false;
        }
        try {
            eventRepository.saveAndFlush(event);
            return true;
        } catch (DataIntegrityViolationException e) {
            log.debug("Meta: leadgen {} allaqachon yozilgan", event.getLeadgenId());
            return false;
        }
    }

    /**
     * Urinishni boshlaydi: {@code attempts} ni OLDINDAN oshiradi.
     *
     * <p>Oldindan — ataylab. Agar jarayon Graph so'rovi paytida o'lib qolsa,
     * hisoblagich allaqachon oshgan bo'ladi va event cheksiz qayta
     * urinishga tushib qolmaydi. Eng yomoni — bitta urinish behuda
     * sarflanadi.
     *
     * @return event ma'lumotlari, yoki null — u allaqachon qayta ishlangan
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public EventSnapshot beginAttempt(Long eventId) {
        MetaWebhookEvent event = eventRepository.findById(eventId).orElse(null);
        if (event == null || !MetaWebhookEvent.STATUS_PENDING.equals(event.getStatus())) {
            return null;
        }
        event.setAttempts(event.getAttempts() + 1);
        eventRepository.save(event);
        return new EventSnapshot(
            event.getId(), event.getLeadgenId(), event.getFormId(), event.getAttempts());
    }

    /**
     * Lidni yozadi va eventni yakunlaydi.
     *
     * <p>Istisno tashlashi MUMKIN — chaqiruvchi uni ushlab
     * {@link #recordFailure} ga o'tkazadi. Bu tranzaksiya to'liq orqaga
     * qaytadi, ya'ni yarim yozilgan lid qolmaydi.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void persist(Long eventId, MetaLeadPayload payload) {
        MetaWebhookEvent event = eventRepository.findById(eventId).orElseThrow();

        MetaLeadIngestService.IngestResult result = ingestService.ingest(payload, false);

        event.setLeadId(result.leadId());
        event.setErrorMessage(result.message());
        event.setProcessedAt(Instant.now());
        event.setStatus(switch (result.outcome()) {
            // Lid allaqachon bor yoki forma lid yaratmaydi — bu xato emas,
            // shuning uchun FAILED ham, PROCESSED ham emas.
            case DUPLICATE_LEADGEN, SKIPPED -> MetaWebhookEvent.STATUS_SKIPPED;
            // Telefon dublikati esa ISH bajarilgan holat: mavjud lid
            // lentasiga izoh qo'shildi va event o'sha lidga ishora qiladi.
            case DUPLICATE_PHONE, CREATED -> MetaWebhookEvent.STATUS_PROCESSED;
        });
        eventRepository.save(event);
    }

    /**
     * Urinish muvaffaqiyatsiz tugadi.
     *
     * <p>{@code attempts} allaqachon {@link #beginAttempt} da oshirilgan,
     * shuning uchun bu yerda faqat chegara tekshiriladi: limitga yetgan
     * event FAILED bo'ladi va qo'lda {@code POST /events/{id}/retry}
     * kutadi, qolganlari PENDING da qolib keyingi siklda qayta uriniladi.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(Long eventId, String message) {
        MetaWebhookEvent event = eventRepository.findById(eventId).orElse(null);
        if (event == null) {
            return;
        }
        event.setErrorMessage(truncate(message, 2000));
        if (event.getAttempts() >= MetaWebhookEvent.MAX_ATTEMPTS) {
            event.setStatus(MetaWebhookEvent.STATUS_FAILED);
            event.setProcessedAt(Instant.now());
            log.error("Meta: event #{} (leadgen={}) {} urinishdan keyin FAILED: {}",
                eventId, event.getLeadgenId(), event.getAttempts(), message);
        } else {
            event.setStatus(MetaWebhookEvent.STATUS_PENDING);
            log.warn("Meta: event #{} (leadgen={}) {}-urinish muvaffaqiyatsiz: {}",
                eventId, event.getLeadgenId(), event.getAttempts(), message);
        }
        eventRepository.save(event);
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
