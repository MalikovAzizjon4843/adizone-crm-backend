package com.crm.service;

import com.crm.audit.AuditAction;
import com.crm.audit.AuditContext;
import com.crm.audit.Audited;
import com.crm.billing.BillingClockConfig;
import com.crm.dto.response.LeadMissingTaskRepairResult;
import com.crm.entity.Lead;
import com.crm.entity.Task;
import com.crm.entity.User;
import com.crm.entity.enums.TaskStatus;
import com.crm.entity.enums.TaskType;
import com.crm.repository.LeadRepository;
import com.crm.repository.TaskRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Mavjud vazifasiz lidlarni bir martalik tuzatish ({@code POST /api/admin/repair/leads-missing-tasks}, SA).
 *
 * <p>Qamrov: {@code requires_task} bosqichdagi (faqat OPEN turi), birorta ham ochiq vazifasi yo'q lidlar.
 * Mas'uli bor — unga "Bog'lanish" vazifasi; mas'uli yo'q — standart mas'ul
 * ({@code leads.default_assignee_user_id}) bo'lsa lid unga biriktiriladi va vazifa unga; bo'lmasa o'tkazib
 * yuboriladi. Vazifa: CALL, {@code auto_created = true}, muddat — bugun 18:00, u o'tgan bo'lsa ertaga
 * 10:00 (Asia/Tashkent).
 *
 * <p>Har lid ALOHIDA tranzaksiyada ({@code REQUIRES_NEW}): bittasidagi xato qolganlarini to'xtatmaydi va
 * ichida holat qayta tekshiriladi (ro'yxat olinganidan beri vazifa qo'shilgan bo'lsa — o'tkaziladi).
 * {@code dryRun = true} — hech narsa yozilmaydi, natija "nima bo'lardi".
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LeadTaskRepairService {

    static final String REPAIR_TASK_TITLE = "Bog'lanish";
    private static final LocalTime TODAY_DUE = LocalTime.of(18, 0);
    private static final LocalTime TOMORROW_DUE = LocalTime.of(10, 0);
    private static final int MAX_ERRORS = 50;

    private enum Outcome { CREATED, CREATED_WITH_DEFAULT, NO_ASSIGNEE, ALREADY_OK }

    private final LeadRepository leadRepository;
    private final TaskRepository taskRepository;
    private final LeadStageService leadStageService;
    private final LeadSettingsService leadSettingsService;
    private final PlatformTransactionManager transactionManager;
    private final Clock billingClock;

    @Audited(action = AuditAction.REPAIR, entity = "Lead",
        summary = "'Vazifasiz lidlar tuzatildi: ' + #result.created + ' ta vazifa, '"
            + " + #result.skippedNoAssignee + ' ta mas''ulsiz o''tkazildi'")
    public LeadMissingTaskRepairResult repairMissingTasks(boolean dryRun) {
        if (dryRun) {
            AuditContext.skip();
        }
        LocalDateTime dueAt = dueAt();
        Set<String> codes = leadStageService.requiredTaskCodes();
        List<Long> ids = codes.isEmpty() ? List.of() : leadRepository.findIdsWithoutOpenTaskInStatuses(codes);
        boolean defaultExists = leadSettingsService.resolveDefaultAssignee().isPresent();

        TransactionTemplate perLead = new TransactionTemplate(transactionManager);
        perLead.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        perLead.setReadOnly(dryRun);

        Map<String, LeadMissingTaskRepairResult.StageRow> byStage = new LinkedHashMap<>();
        leadStageService.orderedOpenCodes().stream().filter(codes::contains)
            .forEach(code -> byStage.put(code, stageRow(code)));
        LeadMissingTaskRepairResult.LeadMissingTaskRepairResultBuilder result = LeadMissingTaskRepairResult.builder()
            .dryRun(dryRun).dueAt(dueAt).total(ids.size());
        long created = 0;
        long assignedToDefault = 0;
        long noAssignee = 0;
        long alreadyOk = 0;
        long failed = 0;
        List<String> errors = new ArrayList<>();

        for (Long id : ids) {
            String[] status = new String[1];
            Outcome outcome;
            try {
                outcome = perLead.execute(s -> {
                    Lead lead = leadRepository.findById(id).orElse(null);
                    if (lead == null) {
                        return Outcome.ALREADY_OK;
                    }
                    status[0] = lead.getStatus();
                    return fix(lead, dryRun, defaultExists, dueAt);
                });
            } catch (RuntimeException e) {
                failed++;
                log.warn("leads-missing-tasks: lid #{} tuzatilmadi", id, e);
                if (errors.size() < MAX_ERRORS) {
                    errors.add("lead #" + id + ": " + e.getMessage());
                }
                continue;
            }
            LeadMissingTaskRepairResult.StageRow row = status[0] != null
                ? byStage.computeIfAbsent(status[0], this::stageRow) : null;
            if (outcome == Outcome.ALREADY_OK) {
                alreadyOk++;
                continue;
            }
            if (row != null) {
                row.setTotal(row.getTotal() + 1);
            }
            switch (outcome) {
                case CREATED, CREATED_WITH_DEFAULT -> {
                    created++;
                    if (row != null) {
                        row.setCreated(row.getCreated() + 1);
                    }
                    if (outcome == Outcome.CREATED_WITH_DEFAULT) {
                        assignedToDefault++;
                        if (row != null) {
                            row.setAssignedToDefault(row.getAssignedToDefault() + 1);
                        }
                    }
                }
                case NO_ASSIGNEE -> {
                    noAssignee++;
                    if (row != null) {
                        row.setSkippedNoAssignee(row.getSkippedNoAssignee() + 1);
                    }
                }
                default -> { }
            }
        }

        AuditContext.change("created", null, created);
        AuditContext.change("assignedToDefault", null, assignedToDefault);
        return result.created(created).assignedToDefault(assignedToDefault).skippedNoAssignee(noAssignee)
            .skippedAlreadyOk(alreadyOk).failed(failed)
            .byStage(new ArrayList<>(byStage.values())).errors(errors).build();
    }

    /** Bitta lid — chaqiruvchining (per-lead) tranzaksiyasida. */
    private Outcome fix(Lead lead, boolean dryRun, boolean defaultExists, LocalDateTime dueAt) {
        if (!leadStageService.requiresTask(lead.getStatus())
                || taskRepository.existsByLead_IdAndStatus(lead.getId(), TaskStatus.OPEN)) {
            return Outcome.ALREADY_OK;
        }
        boolean hadAssignee = lead.getAssignedUser() != null;
        if (dryRun) {
            if (hadAssignee) {
                return Outcome.CREATED;
            }
            return defaultExists ? Outcome.CREATED_WITH_DEFAULT : Outcome.NO_ASSIGNEE;
        }
        User assignee = leadSettingsService.assignIfUnassigned(lead, null);
        if (assignee == null) {
            return Outcome.NO_ASSIGNEE;
        }
        if (!hadAssignee) {
            leadRepository.save(lead);
        }
        taskRepository.save(Task.builder()
            .title(REPAIR_TASK_TITLE)
            .type(TaskType.CALL)
            .status(TaskStatus.OPEN)
            .dueAt(dueAt)
            .allDay(false)
            .assignedTo(assignee)
            .createdBy(null)
            .lead(lead)
            .autoCreated(true)
            .build());
        return hadAssignee ? Outcome.CREATED : Outcome.CREATED_WITH_DEFAULT;
    }

    /** Bugun 18:00 (Toshkent); hozir 18:00 yoki undan keyin bo'lsa — ertaga 10:00. */
    LocalDateTime dueAt() {
        LocalDateTime now = ZonedDateTime.now(billingClock)
            .withZoneSameInstant(BillingClockConfig.ZONE).toLocalDateTime();
        LocalDateTime today = now.toLocalDate().atTime(TODAY_DUE);
        return now.isBefore(today) ? today : now.toLocalDate().plusDays(1).atTime(TOMORROW_DUE);
    }

    private LeadMissingTaskRepairResult.StageRow stageRow(String code) {
        return LeadMissingTaskRepairResult.StageRow.builder()
            .status(code)
            .statusLabel(leadStageService.label(code))
            .build();
    }
}
