package com.crm.service;

import com.crm.dto.request.LeadAssignRequest;
import com.crm.dto.request.LeadBulkAssignRequest;
import com.crm.dto.response.LeadBulkAssignResponse;
import com.crm.entity.User;
import com.crm.exception.CodedException;
import com.crm.exception.ResourceNotFoundException;
import com.crm.repository.LeadRepository;
import com.crm.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Lidlarni ommaviy biriktirish (buyurtmachi 11.10.2026) — {@code POST /api/leads/bulk-assign}, SUPER_ADMIN va SALES_HEAD.
 *
 * <p>Yangi mantiq YO'Q: har lid uchun aynan {@link LeadService#assignLead} chaqiriladi (Spring proxy orqali — har biri
 * O'Z tranzaksiyasida va o'z ASSIGN audit yozuvida): tayinlash tarixi ({@code LeadFunnelTracker.onAssigned}), ochiq
 * auto-vazifalar ko'chishi ({@code TaskService.reassignAutoTasks}), kirish tekshiruvi. Shu sababli bu metod ataylab
 * {@code @Transactional} EMAS — bittasining xatosi qolganlarini to'xtatmaydi va qaytarib yubormaydi. Bosqich bo'yicha
 * cheklov yo'q (yagona biriktirishda ham yo'q) — CONVERTED/REJECTED lidlar ham biriktiriladi.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LeadBulkAssignService {

    public static final int MAX_LEADS = 200;

    private final LeadService leadService;
    private final LeadRepository leadRepository;
    private final UserRepository userRepository;

    public LeadBulkAssignResponse bulkAssign(LeadBulkAssignRequest request) {
        List<Long> ids = request == null || request.getLeadIds() == null ? List.of()
            : new ArrayList<>(new LinkedHashSet<>(request.getLeadIds().stream().filter(Objects::nonNull).toList()));
        if (ids.isEmpty() || ids.size() > MAX_LEADS) {
            throw CodedException.badRequest("lead.bulkAssign.size", MAX_LEADS);
        }
        if (request.getUserId() == null) {
            throw CodedException.badRequest("lead.bulkAssign.userRequired");
        }
        // GET /api/leads/operators bilan bir xil shart: faol va operator roli
        User user = userRepository.findById(request.getUserId())
            .filter(u -> Boolean.TRUE.equals(u.getIsActive()) && LeadAccessService.canBeOperator(u.getRole()))
            .orElseThrow(() -> CodedException.badRequest("lead.bulkAssign.userInvalid", request.getUserId()));

        Map<Long, Long> currentAssignee = new HashMap<>();
        for (Object[] row : leadRepository.findAssigneeIdsByIds(ids)) {
            currentAssignee.put((Long) row[0], (Long) row[1]);
        }
        LeadAssignRequest single = new LeadAssignRequest();
        single.setUserId(user.getId());

        int assigned = 0;
        int skipped = 0;
        List<LeadBulkAssignResponse.Failed> failed = new ArrayList<>();
        for (Long leadId : ids) {
            if (!currentAssignee.containsKey(leadId)) {
                failed.add(new LeadBulkAssignResponse.Failed(leadId, "lead.notFound"));
                continue;
            }
            if (user.getId().equals(currentAssignee.get(leadId))) {
                skipped++;                                    // allaqachon shu xodimda — tarix/audit yozilmaydi
                continue;
            }
            try {
                leadService.assignLead(leadId, single);
                assigned++;
            } catch (RuntimeException e) {
                log.warn("Ommaviy biriktirish: lid {} → user {} yiqildi: {}", leadId, user.getId(), e.getMessage());
                failed.add(new LeadBulkAssignResponse.Failed(leadId, reason(e)));
            }
        }
        return new LeadBulkAssignResponse(ids.size(), assigned, skipped, failed);
    }

    private static String reason(RuntimeException e) {
        if (e instanceof CodedException coded) {
            return coded.getCode();
        }
        if (e instanceof ResourceNotFoundException) {
            return "lead.notFound";
        }
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }
}
