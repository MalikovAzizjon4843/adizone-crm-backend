package com.crm.calls;

import com.crm.dto.request.LeadCommentRequest;
import com.crm.dto.request.NextTaskRequest;
import com.crm.dto.request.TaskCreateRequest;
import com.crm.entity.Lead;
import com.crm.entity.LeadStage;
import com.crm.entity.PhoneCall;
import com.crm.entity.Task;
import com.crm.exception.CodedException;
import com.crm.exception.ForbiddenException;
import com.crm.repository.LeadRepository;
import com.crm.repository.LeadStageRepository;
import com.crm.repository.PhoneCallRepository;
import com.crm.repository.TaskRepository;
import com.crm.service.LeadAccessService;
import com.crm.service.LeadService;
import com.crm.service.TaskService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.Locale;

/**
 * {@code PATCH /api/calls/app/calls/{id}/result} (docs/design/calls.md §4.5). Natija qo'ng'iroqqa yoziladi va BIR
 * tranzaksiyada mavjud servislar orqali tarqatiladi:
 * <ul>
 *   <li>lid — {@link LeadService#addComment} (lid timeline'i), {@code leadStageId} → {@link LeadService#updateStatus}
 *       (vazifa majburiyligi V79, yopilganda vazifalar bekor — o'sha qoidalar), {@code nextTask} →
 *       {@link TaskService#createNextTask};</li>
 *   <li>o'quvchi / ota-ona — izoh hozircha faqat {@code calls.note} da (keyin {@code student_notes}); {@code nextTask} →
 *       o'quvchiga bog'langan vazifa ({@link TaskService#create});</li>
 *   <li>bog'lanmagan raqam — {@code nextTask} → mustaqil vazifa.</li>
 * </ul>
 * Lid boshqa operatorniki bo'lsa (SALES_MANAGER): natija qo'ng'iroqda saqlanadi, timeline'ga yozilmaydi; bosqich yoki
 * vazifa so'ralgan bo'lsa — 403 {@code calls.result.leadForbidden} (hech narsa saqlanmaydi).
 */
@Service
@RequiredArgsConstructor
public class CallResultService {

    static final int NOTE_MAX = 2000;

    private final PhoneCallRepository callRepository;
    private final LeadRepository leadRepository;
    private final LeadStageRepository leadStageRepository;
    private final TaskRepository taskRepository;
    private final LeadService leadService;
    private final TaskService taskService;
    private final LeadAccessService leadAccessService;
    private final CallQueryService queryService;
    private final CallLookupService lookupService;
    private final Clock billingClock;

    @Transactional
    public CallDtos.ResultResponse patch(CallDevicePrincipal principal, Long callId, CallDtos.ResultRequest request) {
        PhoneCall call = callRepository.findById(callId)
            .filter(c -> c.getUserId().equals(principal.userId()))
            .orElseThrow(() -> CodedException.notFound("calls.notFound"));
        if (request == null) {
            throw CodedException.badRequest("calls.result.codeInvalid");
        }
        PhoneCall.ResultCode code = parseCode(request.resultCode());
        String note = CallDeviceService.trimToNull(request.note());
        if (note != null && note.length() > NOTE_MAX) {
            throw CodedException.badRequest("calls.result.noteTooLong", NOTE_MAX);
        }
        if (request.leadStageId() != null && call.getLeadId() == null) {
            throw CodedException.badRequest("calls.result.notLead");
        }
        CallDtos.NextTask next = request.nextTask();
        if (next != null && (CallDeviceService.trimToNull(next.title()) == null || next.dueAt() == null)) {
            throw CodedException.badRequest("calls.result.taskInvalid");
        }

        call.setResultCode(code);
        call.setNote(note);
        call.setResultBy(principal.userId());
        call.setResultAt(LocalDateTime.now(billingClock));
        callRepository.save(call);

        boolean timeline = false;
        boolean stageChanged = false;
        Long taskId = null;
        if (call.getLeadId() != null) {
            boolean canAccess = canAccessLead(call.getLeadId());
            boolean sideEffects = request.leadStageId() != null || next != null;
            if (sideEffects && !canAccess) {
                throw CodedException.forbidden("calls.result.leadForbidden");
            }
            if (canAccess) {
                LeadCommentRequest comment = new LeadCommentRequest();
                comment.setText(timelineText(call, code, note));
                leadService.addComment(call.getLeadId(), comment);
                timeline = true;
            }
            if (request.leadStageId() != null) {
                LeadStage stage = leadStageRepository.findById(request.leadStageId())
                    .orElseThrow(() -> CodedException.badRequest("calls.result.stageInvalid"));
                Lead lead = leadRepository.findById(call.getLeadId()).orElseThrow();
                String old = lead.getStatus();
                leadService.updateStatus(call.getLeadId(), stage.getCode(), null, next != null ? nextTaskWithOwner(next, principal.userId()) : null);
                stageChanged = !stage.getCode().equals(old);
                if (next != null) {
                    taskId = newestOpenLeadTask(call.getLeadId());
                }
            } else if (next != null) {
                Lead lead = leadRepository.findById(call.getLeadId()).orElseThrow();
                taskId = taskService.createNextTask(lead, nextTaskWithOwner(next, principal.userId())).getId();
            }
        } else if (next != null) {
            TaskCreateRequest task = new TaskCreateRequest();
            task.setTitle(next.title().trim());
            String phone = call.getPhoneNormalized() != null ? call.getPhoneNormalized() : call.getPhoneRaw();
            String comment = CallDeviceService.trimToNull(next.comment());
            task.setDescription(call.getStudentId() != null ? comment
                : (comment != null ? comment + "\n" : "") + "Telefon: " + phone);
            task.setType(next.type());
            task.setDueAt(next.dueAt());
            task.setAllDay(next.allDay());
            task.setAssignedTo(next.assigneeId() != null ? next.assigneeId() : principal.userId());
            task.setStudentId(call.getStudentId());
            taskId = taskService.create(task).getId();
        }
        lookupService.evict(call.getPhoneNormalized());
        return new CallDtos.ResultResponse(queryService.toItem(call), timeline, stageChanged, taskId);
    }

    private boolean canAccessLead(Long leadId) {
        try {
            leadAccessService.assertCanAccessLead(leadId);
            return true;
        } catch (ForbiddenException e) {
            return false;
        }
    }

    private Long newestOpenLeadTask(Long leadId) {
        return taskRepository.findOpenByLeadIds(java.util.List.of(leadId)).stream()
            .map(Task::getId).max(Comparator.naturalOrder()).orElse(null);
    }

    private static NextTaskRequest nextTask(CallDtos.NextTask next) {
        NextTaskRequest r = new NextTaskRequest();
        r.setTitle(next.title().trim());
        r.setDueAt(next.dueAt());
        r.setType(next.type());
        r.setComment(next.comment());
        r.setAllDay(next.allDay());
        r.setAssigneeId(next.assigneeId());
        return r;
    }

    private NextTaskRequest nextTaskWithOwner(CallDtos.NextTask next, Long ownerId) {
        NextTaskRequest r = nextTask(next);
        if (r.getAssigneeId() == null) {
            r.setAssigneeId(ownerId);
        }
        return r;
    }

    static PhoneCall.ResultCode parseCode(String raw) {
        if (raw == null || raw.isBlank()) {
            throw CodedException.badRequest("calls.result.codeInvalid");
        }
        try {
            return PhoneCall.ResultCode.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw CodedException.badRequest("calls.result.codeInvalid");
        }
    }

    /** Lid timeline'idagi matn: "📞 Chiquvchi qo'ng'iroq · 2:35 — Gaplashildi. Izoh". */
    static String timelineText(PhoneCall call, PhoneCall.ResultCode code, String note) {
        String dir = switch (call.getDirection()) {
            case IN -> "Kiruvchi qo'ng'iroq";
            case OUT -> "Chiquvchi qo'ng'iroq";
            case MISSED -> "Javobsiz qo'ng'iroq";
            case REJECTED -> "Rad etilgan qo'ng'iroq";
        };
        String result = switch (code) {
            case TALKED -> "Gaplashildi";
            case NO_ANSWER -> "Javob bermadi";
            case CALLBACK -> "Qayta qo'ng'iroq qilish kerak";
            case THINKING -> "O'ylab ko'radi";
            case TRIAL_BOOKED -> "Sinov darsiga yozildi";
            case REJECTED -> "Rad etdi";
            case OTHER -> "Boshqa";
        };
        StringBuilder sb = new StringBuilder("📞 ").append(dir);
        if (call.getDurationSec() > 0) {
            sb.append(" · ").append(call.getDurationSec() / 60).append(':')
                .append(String.format(Locale.ROOT, "%02d", call.getDurationSec() % 60));
        }
        sb.append(" — ").append(result);
        if (note != null) {
            sb.append(". ").append(note);
        }
        return sb.toString();
    }
}
