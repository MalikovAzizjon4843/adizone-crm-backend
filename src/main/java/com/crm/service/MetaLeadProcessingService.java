package com.crm.service;

import com.crm.config.MetaProperties;
import com.crm.entity.MetaWebhookEvent;
import com.crm.repository.MetaLeadFormRepository;
import com.crm.repository.MetaWebhookEventRepository;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * PENDING eventlarni navbat bilan lidga aylantiradi.
 *
 * <p>Webhook controlleri hech narsa qayta ishlamaydi — u faqat qatorni
 * yozadi va 200 qaytaradi, chunki Meta javobni 20 soniya kutadi va
 * kechikkan yetkazib berishlardan keyin obunani o'chirib qo'yadi. Butun
 * og'ir ish (Graph so'rovi, mapping, dublikat qidiruvi, lid yozish) shu
 * siklda bo'ladi.
 *
 * <p><b>Har event o'z tranzaksiyasida</b> ({@link MetaEventWorker}).
 * Graph so'rovi esa tranzaksiyadan TASHQARIDA: HTTP kutayotgan paytda
 * baza ulanishini ushlab turish 20 ta eventda pulni butunlay tugatardi.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MetaLeadProcessingService {

    /** Bir siklda nechta event olinadi. */
    private static final int BATCH_SIZE = 20;

    private final MetaProperties properties;
    private final MetaWebhookEventRepository eventRepository;
    private final MetaLeadFormRepository formRepository;
    private final MetaGraphClient graphClient;
    private final MetaFormSyncService formSyncService;
    private final MetaEventWorker worker;

    @Scheduled(fixedDelay = 15_000L)
    public void processPending() {
        if (!properties.isEnabled()) {
            return;
        }
        List<Long> ids = eventRepository.findPendingIds(
            MetaWebhookEvent.STATUS_PENDING,
            MetaWebhookEvent.MAX_ATTEMPTS,
            PageRequest.of(0, BATCH_SIZE));
        if (ids.isEmpty()) {
            return;
        }

        log.debug("Meta: {} ta event qayta ishlanadi", ids.size());
        for (Long id : ids) {
            processOne(id);
        }
    }

    /**
     * Bitta event: urinishni ochish → Graph → yozish.
     *
     * <p>Istisno BU YERDA ushlanadi va yangi tranzaksiyada qayd etiladi.
     * {@code worker.persist} ning tranzaksiyasi allaqachon orqaga qaytgan
     * bo'ladi, shuning uchun {@code recordFailure} toza seansda ishlaydi.
     */
    void processOne(Long eventId) {
        MetaEventWorker.EventSnapshot snapshot;
        try {
            snapshot = worker.beginAttempt(eventId);
        } catch (Exception e) {
            log.error("Meta: event #{} urinishini ocholmadik: {}", eventId, e.getMessage(), e);
            return;
        }
        if (snapshot == null) {
            return;
        }

        try {
            JsonNode node = graphClient.getLead(snapshot.leadgenId());
            ensureFormKnown(node, snapshot.formId());

            MetaLeadPayload payload = MetaLeadPayload.from(
                node, snapshot.formId(), node.toString());
            worker.persist(eventId, payload);
        } catch (MetaApiException e) {
            if (e.isInvalidToken()) {
                // Kesh allaqachon tozalangan (MetaGraphClient.parse) —
                // keyingi sikl tokenni qaytadan oladi va shu event
                // o'z-o'zidan tiklanadi. Alohida log yozamiz, chunki
                // sabab tashqarida va uni odam ko'rishi kerak.
                log.warn("Meta: token yaroqsiz (xato 190), kesh tozalandi — "
                    + "event #{} keyingi siklda qayta uriniladi", eventId);
            }
            worker.recordFailure(eventId, e.getMessage());
        } catch (Exception e) {
            log.error("Meta: event #{} qayta ishlanmadi", eventId, e);
            worker.recordFailure(eventId, rootMessage(e));
        }
    }

    /**
     * Forma bazada yo'q bo'lsa bir marta sinxronizatsiya chaqiradi.
     *
     * <p>Odatiy holat: marketolog yangi forma yaratdi va birinchi lid
     * sinxronizatsiyadan OLDIN keldi. Sync dan keyin ham topilmasa hech
     * narsa qilinmaydi — {@code MetaLeadIngestService} lidni baribir
     * yaratadi va izohga "noma'lum forma" deb yozadi.
     */
    private void ensureFormKnown(JsonNode leadNode, String fallbackFormId) {
        String formId = leadNode.path("form_id").asText(null);
        if (formId == null || formId.isBlank()) {
            formId = fallbackFormId;
        }
        if (formId == null || formId.isBlank()
                || formRepository.findByFormId(formId).isPresent()) {
            return;
        }
        log.info("Meta: forma {} bazada yo'q — sinxronizatsiya chaqirilmoqda", formId);
        try {
            formSyncService.syncForms();
        } catch (Exception e) {
            // Sync yiqilsa ham lid yaratilishi kerak — xato faqat loglanadi.
            log.warn("Meta: avtomatik sinxronizatsiya muvaffaqiyatsiz: {}", e.getMessage());
        }
    }

    /** Hibernate/JDBC istisnolari o'ralgan bo'ladi — eng ichkisi tushunarliroq. */
    static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        String message = t.getMessage();
        return message != null && !message.isBlank()
            ? message
            : t.getClass().getSimpleName();
    }
}
