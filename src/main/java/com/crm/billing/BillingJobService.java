package com.crm.billing;

import com.crm.entity.BillingJobRun;
import com.crm.entity.enums.PaymentType;
import com.crm.repository.BillingJobRunRepository;
import com.crm.repository.StudentGroupRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Kunlik billing — docs/design/billing-v2.md §3.7. Eski 00:05 dagi
 * {@code updateOverdueStatusesDaily} o'rnini bosadi.
 *
 * <p>Eski job barcha o'quvchilarni BITTA tranzaksiyada yurardi — bittasi
 * yiqilsa hammasi qaytardi. Endi har SG {@code REQUIRES_NEW} da: xato faqat
 * shu SG ga ta'sir qiladi va {@code billing_job_runs.errors} ga yoziladi.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BillingJobService {

    public static final String JOB_ACCRUAL = "ACCRUAL";

    private final AccrualService accrualService;
    private final BillingSnapshotService snapshotService;
    private final StudentGroupRepository studentGroupRepository;
    private final BillingJobRunRepository jobRunRepository;
    private final BillingProperties properties;
    private final BillingGate gate;
    private final Clock billingClock;

    @Scheduled(cron = "${app.billing.accrual-cron:0 10 0 * * *}", zone = "Asia/Tashkent")
    public void scheduledDailyBilling() {
        if (!gate.isEnabled()) {
            log.warn("Billing o'chiq (app.billing.enabled=false) — kunlik accrual o'tkazib yuborildi");
            return;
        }
        runDaily(LocalDate.now(billingClock), "SCHEDULED");
    }

    /** Server o'chib qolgan kunlar uchun "quvib yetish" (§3.8, chaqiruv joyi 2). */
    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        if (!properties.isStartupCatchUp() || !gate.isEnabled()) {
            return;
        }
        try {
            runDaily(LocalDate.now(billingClock), "STARTUP");
        } catch (RuntimeException e) {
            // Startup hech qachon billing sababli yiqilmasin — job ertaga takrorlaydi
            log.error("Startup accrual yiqildi: {}", e.getMessage(), e);
        }
    }

    public BillingJobRun runDaily(LocalDate date, String trigger) {
        BillingJobRun run = jobRunRepository.save(BillingJobRun.builder()
            .jobName(JOB_ACCRUAL)
            .triggerSource(trigger)
            .runDate(date)
            .status("RUNNING")
            .startedAt(LocalDateTime.now(billingClock))
            .build());

        List<Long> candidates = studentGroupRepository.findAccrualCandidateIds(date, PaymentType.MONTHLY);
        int processed = 0;
        int periods = 0;
        int limited = 0;
        List<String> errors = new ArrayList<>();
        for (Long sgId : candidates) {
            try {
                AccrualService.AccrualResult r = accrualService.accrueInNewTransaction(sgId, date);
                periods += r.created().size();
                if (r.catchUpLimitReached()) {
                    limited++;
                    errors.add(sgId + ": catch-up chegarasi (" + properties.getMaxCatchUp() + ")");
                }
                processed++;
            } catch (RuntimeException e) {
                log.error("Accrual yiqildi sg={}: {}", sgId, e.getMessage(), e);
                errors.add(sgId + ": " + e.getClass().getSimpleName() + " — " + e.getMessage());
            }
        }

        // PENDING → OVERDUE vaqt o'tishi bilan (davr yozilmagan SG larda ham)
        int refreshed = snapshotService.refreshAllDue(date);
        log.debug("Snapshot yangilandi: {} o'quvchi", refreshed);

        int failed = candidates.size() - processed;
        run.setCandidates(candidates.size());
        run.setProcessed(processed);
        run.setPeriodsCreated(periods);
        run.setFailed(failed);
        run.setCatchUpLimited(limited);
        run.setErrors(errors.isEmpty() ? null : String.join("\n", errors));
        run.setStatus(failed == 0 ? "OK" : (processed == 0 ? "FAILED" : "PARTIAL"));
        run.setFinishedAt(LocalDateTime.now(billingClock));
        BillingJobRun saved = jobRunRepository.save(run);
        log.info("Billing {} {} ({}): nomzod {}, davr {}, xato {}", JOB_ACCRUAL, date, trigger,
            candidates.size(), periods, failed);
        return saved;
    }
}
