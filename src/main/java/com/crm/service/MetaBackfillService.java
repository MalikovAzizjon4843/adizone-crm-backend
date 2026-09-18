package com.crm.service;

import com.crm.dto.response.MetaBackfillResultDto;
import com.crm.entity.MetaLeadForm;
import com.crm.exception.BadRequestException;
import com.crm.repository.MetaLeadFormRepository;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * Eski lidlarni Meta dan tortib oladi — webhook yoqilishidan OLDIN kelgan
 * murojaatlar uchun.
 *
 * <p>Qayta ishlash mantiqi webhook oqimi bilan BIR XIL
 * ({@link MetaLeadIngestService}): dublikat qoidasi, mapping, izohlar va
 * avtomatik vazifa — hammasi bitta joyda. Bu yerda faqat sahifalash va
 * statistika bor.
 *
 * <p><b>Sahifama-sahifa.</b> Eng katta formada 776 lid bor; ularni bitta
 * so'rovda ham, bitta tranzaksiyada ham olish mumkin emas. Graph 100
 * tadan beradi, har lid esa o'z tranzaksiyasida yoziladi
 * ({@link MetaBackfillWorker}).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MetaBackfillService {

    /** Javobda nechta xato matni qaytariladi — qolganlari faqat log da. */
    private static final int MAX_REPORTED_ERRORS = 20;

    /** Sahifalash cheksiz aylanib qolmasin (100 × 500 = 50 000 lid). */
    private static final int MAX_PAGES = 500;

    private final MetaLeadFormRepository formRepository;
    private final MetaGraphClient graphClient;
    private final MetaBackfillWorker worker;

    /**
     * @param formId    Meta dagi forma id si
     * @param sinceDate shu sanadan oldingi lidlar o'tkazib yuboriladi (null — hammasi)
     * @param dryRun    true — hech narsa saqlanmaydi, faqat statistika
     */
    public MetaBackfillResultDto backfillForm(String formId, LocalDate sinceDate, boolean dryRun) {
        MetaLeadForm form = formRepository.findByFormId(formId)
            .orElseThrow(() -> new BadRequestException(
                "Forma topilmadi: " + formId + " — avval POST /api/meta/forms/sync"));

        Instant since = sinceDate != null
            ? sinceDate.atStartOfDay(ZoneId.systemDefault()).toInstant()
            : null;

        int total = 0;
        int created = 0;
        int duplicates = 0;
        int skipped = 0;
        int errors = 0;
        int outOfRange = 0;
        int pages = 0;
        List<String> errorMessages = new ArrayList<>();

        String cursor = null;
        boolean stop = false;

        log.info("Meta backfill boshlandi: forma '{}' ({}), since={}, dryRun={}",
            form.getName(), formId, sinceDate, dryRun);

        while (!stop) {
            if (++pages > MAX_PAGES) {
                log.warn("Meta backfill: {} sahifadan oshdi, to'xtatildi", MAX_PAGES);
                break;
            }

            MetaGraphClient.LeadPage page = graphClient.getFormLeads(formId, cursor);
            if (page.leads().isEmpty()) {
                break;
            }

            int inRangeOnPage = 0;
            for (JsonNode node : page.leads()) {
                MetaLeadPayload payload =
                    MetaLeadPayload.from(node, formId, node.toString());

                // Graph lidlarni yangidan eskiga qarab beradi. Sana filtridan
                // o'tmagan lid o'tkazib yuboriladi va agar BUTUN sahifa eski
                // bo'lsa, undan narigisi ham eski — sahifalashni to'xtatamiz.
                if (since != null && payload.createdTime() != null
                        && payload.createdTime().isBefore(since)) {
                    outOfRange++;
                    continue;
                }
                inRangeOnPage++;
                total++;

                try {
                    MetaLeadIngestService.IngestResult result =
                        worker.ingestOne(payload, dryRun);
                    switch (result.outcome()) {
                        case CREATED -> created++;
                        case DUPLICATE_LEADGEN, DUPLICATE_PHONE -> duplicates++;
                        case SKIPPED -> skipped++;
                    }
                } catch (Exception e) {
                    errors++;
                    String message = payload.leadgenId() + ": "
                        + MetaLeadProcessingService.rootMessage(e);
                    log.warn("Meta backfill: lid yozilmadi — {}", message);
                    if (errorMessages.size() < MAX_REPORTED_ERRORS) {
                        errorMessages.add(message);
                    }
                }
            }

            if (since != null && inRangeOnPage == 0) {
                stop = true;
            }

            cursor = page.nextCursor();
            if (cursor == null) {
                stop = true;
            }

            log.info("Meta backfill: forma {} — {} sahifa, {} lid o'qildi "
                    + "({} yangi, {} dublikat, {} o'tkazildi, {} xato)",
                formId, pages, total, created, duplicates, skipped, errors);
        }

        log.info("Meta backfill tugadi: forma {} — jami {}, yangi {}, dublikat {}, "
                + "o'tkazildi {}, xato {}, sana filtridan tashqarida {}",
            formId, total, created, duplicates, skipped, errors, outOfRange);

        return MetaBackfillResultDto.builder()
            .formId(formId)
            .formName(form.getName())
            .dryRun(dryRun)
            .since(sinceDate != null ? sinceDate.toString() : null)
            .total(total)
            .created(created)
            .duplicates(duplicates)
            .skipped(skipped)
            .errors(errors)
            .outOfRange(outOfRange)
            .pages(pages)
            .errorMessages(errorMessages)
            .build();
    }
}
