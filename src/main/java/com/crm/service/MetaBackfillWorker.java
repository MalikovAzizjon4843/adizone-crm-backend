package com.crm.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Backfill dagi bitta lidni ALOHIDA tranzaksiyada yozadi.
 *
 * <p>Alohida bean — {@code REQUIRES_NEW} faqat proxy orqali chaqirilganda
 * ishlaydi. 776 lidli formada bitta buzuq yozuv butun partiyani yiqitsa,
 * ishni boshidan boshlash kerak bo'lardi.
 */
@Service
@RequiredArgsConstructor
public class MetaBackfillWorker {

    private final MetaLeadIngestService ingestService;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public MetaLeadIngestService.IngestResult ingestOne(MetaLeadPayload payload, boolean dryRun) {
        return ingestService.ingest(payload, dryRun);
    }
}
