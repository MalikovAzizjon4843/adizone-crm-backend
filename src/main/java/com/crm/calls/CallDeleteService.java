package com.crm.calls;

import com.crm.entity.CallRecording;
import com.crm.entity.PhoneCall;
import com.crm.entity.User;
import com.crm.exception.CodedException;
import com.crm.repository.CallRecordingRepository;
import com.crm.repository.PhoneCallRepository;
import com.crm.repository.UserRepository;
import com.crm.service.LeadHardDeleteService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

/**
 * {@code DELETE /api/calls/app/calls/{id}} (docs/design/calls.md §4.8): xodim noto'g'ri yuborilgan (shaxsiy)
 * qo'ng'iroqni yozuvi bilan o'chiradi.
 *
 * <ul>
 *   <li>Faqat o'z qo'ng'irog'i (boshqasiniki — 404) va serverga tushganidan ({@code created_at}) {@value #WINDOW_DAYS}
 *       kun ichida (aks holda 409 {@code calls.delete.tooOld}).</li>
 *   <li>Qo'ng'iroq avto-lid ochgan bo'lsa ({@code auto_lead_created}) va lidda boshqa faollik bo'lmasa
 *       ({@link #hasActivity}) — lid {@link LeadHardDeleteService} orqali butunlay o'chadi (qo'ng'iroq va yozuv ham
 *       shu yerda, audit {@code LEAD_DELETED}, mode {@code CALL_APP}). Aks holda faqat qo'ng'iroq o'chadi.</li>
 *   <li>Yozuv fayli diskdan COMMIT dan keyin o'chadi.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CallDeleteService {

    static final int WINDOW_DAYS = 7;

    private final PhoneCallRepository callRepository;
    private final CallRecordingRepository recordingRepository;
    private final UserRepository userRepository;
    private final LeadHardDeleteService leadHardDeleteService;
    private final CallRecordingService recordingService;
    private final CallLookupService lookupService;
    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final Clock billingClock;

    public CallDtos.DeleteCallResponse delete(CallDevicePrincipal principal, Long callId) {
        PhoneCall call = callRepository.findById(callId)
            .filter(c -> c.getUserId().equals(principal.userId()))
            .orElseThrow(() -> CodedException.notFound("calls.notFound"));
        LocalDateTime now = LocalDateTime.now(billingClock);
        if (call.getCreatedAt().isBefore(now.minusDays(WINDOW_DAYS))) {
            throw new com.crm.exception.ConflictException("calls.delete.tooOld", WINDOW_DAYS);
        }
        boolean hadRecording = recordingRepository.findByCallId(callId).map(CallRecording::isAvailable).orElse(false);
        Long leadId = call.getLeadId();

        String keptReason = null;
        if (leadId != null && call.isAutoLeadCreated()) {
            if (hasActivity(leadId, callId)) {
                keptReason = "HAS_ACTIVITY";
            } else {
                User owner = userRepository.findById(principal.userId()).orElse(null);
                try {
                    leadHardDeleteService.delete(leadId, owner, LeadHardDeleteService.Mode.CALL_APP);
                    log.info("Qo'ng'iroqlar: qo'ng'iroq #{} va avto-lid #{} o'chirildi (user #{})", callId, leadId,
                        principal.userId());
                    return new CallDtos.DeleteCallResponse(callId, hadRecording, leadId, true, null);
                } catch (CodedException e) {
                    // Lid allaqachon yo'q / konvert qilingan / to'lovi bor — faqat qo'ng'iroq
                    keptReason = e.getCode();
                }
            }
        } else if (leadId != null) {
            keptReason = "NOT_AUTO_LEAD";
        }

        List<String> files = tx.execute(s -> {
            List<String> paths = recordingRepository.findByCallId(callId)
                .map(CallRecording::getPath).filter(java.util.Objects::nonNull).map(List::of).orElse(List.of());
            jdbc.update("DELETE FROM call_recordings WHERE call_id = :id", new MapSqlParameterSource("id", callId));
            jdbc.update("DELETE FROM calls WHERE id = :id", new MapSqlParameterSource("id", callId));
            return paths;
        });
        recordingService.deleteFiles(files);
        if (call.getPhoneNormalized() != null) {
            lookupService.evict(call.getPhoneNormalized());
        }
        log.info("Qo'ng'iroqlar: qo'ng'iroq #{} o'chirildi (user #{}; lid #{}: {})", callId, principal.userId(), leadId,
            keptReason);
        return new CallDtos.DeleteCallResponse(callId, hadRecording, leadId, false, leadId == null ? null : keptReason);
    }

    /**
     * Avto-lidda inson faolligi bormi: boshqa qo'ng'iroq (istalgan xodim), izoh, eslatma, bosqich o'zgarishi, qayta
     * biriktirish (yaratilishdagi bitta yozuvdan ortiq), CRM'dan qo'ng'iroq so'rovi, qo'lda yaratilgan yoki yopilgan
     * vazifa (avtomatik "Yangi lid: bog'lanish" ochiq turgani — faollik emas), bosqich / ism / summa o'zgargan.
     */
    boolean hasActivity(Long leadId, Long callId) {
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("id", leadId).addValue("call", callId)
            .addValue("initial", com.crm.entity.Lead.DEFAULT_STATUS);
        Integer n = jdbc.queryForObject("""
                SELECT (SELECT COUNT(*) FROM calls WHERE lead_id = :id AND id <> :call)
                     + (SELECT COUNT(*) FROM lead_comments WHERE lead_id = :id)
                     + (SELECT COUNT(*) FROM lead_notes WHERE lead_id = :id)
                     + (SELECT COUNT(*) FROM lead_status_history WHERE lead_id = :id)
                     + (SELECT COUNT(*) FROM call_requests WHERE lead_id = :id)
                     + (SELECT COUNT(*) FROM tasks WHERE lead_id = :id
                          AND (auto_created = FALSE OR status <> 'OPEN'))
                     + CASE WHEN (SELECT COUNT(*) FROM lead_assignments WHERE lead_id = :id) > 1 THEN 1 ELSE 0 END
                     + (SELECT COUNT(*) FROM leads WHERE id = :id
                          AND (status <> :initial OR amount IS NOT NULL OR COALESCE(converted, FALSE)
                               OR full_name NOT LIKE 'Qo''ng''iroq %'))
                """, p, Integer.class);
        return n != null && n > 0;
    }
}
