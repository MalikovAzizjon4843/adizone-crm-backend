package com.crm.calls;

import com.crm.entity.CallDevice;
import com.crm.entity.CallRequest;
import com.crm.entity.Lead;
import com.crm.entity.Student;
import com.crm.entity.User;
import com.crm.entity.enums.UserRole;
import com.crm.exception.CodedException;
import com.crm.exception.ConflictException;
import com.crm.exception.ResourceNotFoundException;
import com.crm.repository.CallDeviceRepository;
import com.crm.repository.CallRequestRepository;
import com.crm.repository.LeadRepository;
import com.crm.repository.StudentRepository;
import com.crm.repository.UserRepository;
import com.crm.service.LeadAccessService;
import com.crm.util.PhoneUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * CRM'dan "telefonimdan qo'ng'iroq qil" (docs/design/calls.md §6).
 *
 * <p>Holatlar: {@code PENDING} (yaratildi) → {@code DELIVERED} (ilova oldi) → {@code DIALED} (raqam terildi) yoki
 * {@code CANCELLED}; {@code app.calls.request-ttl-seconds} o'tib ochiq qolsa — {@code EXPIRED}. Xodimda bir vaqtda
 * bitta ochiq so'rov: yangisi eskisini CANCELLED qiladi. Yetkazish — FCM ({@code app.calls.fcm.enabled}) yoki ilova
 * {@code GET /api/calls/app/requests/pending} ni so'rab turadi.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CallRequestService {

    private static final Set<UserRole> CAN_TARGET_OTHERS =
        EnumSet.of(UserRole.SUPER_ADMIN, UserRole.ADMIN, UserRole.SALES_HEAD);
    private static final List<CallRequest.Status> OPEN =
        List.of(CallRequest.Status.PENDING, CallRequest.Status.DELIVERED);

    private final CallRequestRepository requestRepository;
    private final CallDeviceRepository deviceRepository;
    private final UserRepository userRepository;
    private final LeadRepository leadRepository;
    private final StudentRepository studentRepository;
    private final LeadAccessService leadAccessService;
    private final FcmPushService fcm;
    private final CallsProperties properties;
    private final Clock billingClock;

    // ── CRM ──────────────────────────────────────────────────────────────

    @Transactional
    public CallDtos.RequestItem create(CallDtos.CreateRequest body) {
        User actor = leadAccessService.getCurrentUserOrThrow();
        if (body == null) {
            throw CodedException.badRequest("calls.phone.invalid");
        }
        if (body.leadId() != null && body.studentId() != null) {
            throw CodedException.badRequest("calls.request.targetSingle");
        }
        User target = actor;
        if (body.userId() != null && !body.userId().equals(actor.getId())) {
            if (!CAN_TARGET_OTHERS.contains(actor.getRole())) {
                throw CodedException.forbidden("calls.request.otherUserForbidden");
            }
            target = userRepository.findById(body.userId())
                .orElseThrow(() -> new ResourceNotFoundException("User", body.userId()));
        }
        String phone = body.phone() != null ? PhoneUtils.canonical(body.phone()) : null;
        Lead lead = null;
        Student student = null;
        if (body.leadId() != null) {
            lead = leadRepository.findById(body.leadId())
                .orElseThrow(() -> new ResourceNotFoundException("Lead", body.leadId()));
            leadAccessService.assertCanAccessLead(lead);
            if (phone == null) {
                phone = PhoneUtils.canonical(lead.getPhone());
            }
        } else if (body.studentId() != null) {
            student = studentRepository.findById(body.studentId())
                .orElseThrow(() -> new ResourceNotFoundException("Student", body.studentId()));
            if (phone == null) {
                phone = PhoneUtils.canonical(student.getPhone());
            }
        }
        if (phone == null) {
            throw CodedException.badRequest("calls.phone.invalid");
        }
        CallDevice device = deviceRepository.findByUserIdAndRevokedAtIsNullOrderByIdDesc(target.getId()).stream()
            .findFirst()
            .orElseThrow(() -> new ConflictException(
                "calls.request.noDevice"));

        LocalDateTime now = LocalDateTime.now(billingClock);
        for (CallRequest old : requestRepository.findByUserIdAndStatusInOrderByIdDesc(target.getId(), OPEN)) {
            close(old, old.getExpiresAt().isAfter(now) ? CallRequest.Status.CANCELLED : CallRequest.Status.EXPIRED, now);
        }
        CallRequest saved = requestRepository.save(CallRequest.builder()
            .userId(target.getId())
            .deviceId(device.getId())
            .phone(phone)
            .leadId(lead != null ? lead.getId() : null)
            .studentId(student != null ? student.getId() : null)
            .status(CallRequest.Status.PENDING)
            .createdBy(actor.getId())
            .createdAt(now)
            .expiresAt(now.plusSeconds(Math.max(10, properties.getRequestTtlSeconds())))
            .build());
        String contact = lead != null ? lead.getFullName()
            : student != null ? CallContactMatcher.studentName(student) : null;
        afterCommit(() -> fcm.sendCallRequest(device, saved.getId(), saved.getPhone(), contact));
        return toItem(saved, contact, actor);
    }

    @Transactional
    public CallDtos.RequestItem get(Long id) {
        User actor = leadAccessService.getCurrentUserOrThrow();
        CallRequest r = visible(id, actor);
        expireIfDue(r, LocalDateTime.now(billingClock));
        return toItem(r);
    }

    @Transactional
    public CallDtos.RequestItem cancel(Long id) {
        User actor = leadAccessService.getCurrentUserOrThrow();
        CallRequest r = visible(id, actor);
        LocalDateTime now = LocalDateTime.now(billingClock);
        expireIfDue(r, now);
        if (r.getStatus() == CallRequest.Status.CANCELLED) {
            return toItem(r);
        }
        if (!r.getStatus().open()) {
            throw new ConflictException("calls.request.invalidTransition", r.getStatus().name(), "CANCELLED");
        }
        close(r, CallRequest.Status.CANCELLED, now);
        return toItem(r);
    }

    // ── Ilova ────────────────────────────────────────────────────────────

    @Transactional
    public List<CallDtos.RequestItem> pending(CallDevicePrincipal principal) {
        LocalDateTime now = LocalDateTime.now(billingClock);
        return requestRepository.findByUserIdAndStatusInOrderByIdDesc(principal.userId(), OPEN).stream()
            .filter(r -> !expireIfDue(r, now))
            .map(this::toItem)
            .toList();
    }

    /**
     * Ilova tasdig'i: DELIVERED | DIALED | CANCELLED. Takror (o'sha holat) — 200; yopilgan / muddati o'tgan — 409.
     */
    @Transactional(noRollbackFor = ConflictException.class)
    public CallDtos.RequestItem ack(CallDevicePrincipal principal, Long id, CallDtos.AckRequest body) {
        CallRequest r = requestRepository.findById(id)
            .filter(x -> x.getUserId().equals(principal.userId()))
            .orElseThrow(() -> CodedException.notFound("calls.request.notFound"));
        CallRequest.Status to = parseAck(body == null ? null : body.status());
        LocalDateTime now = LocalDateTime.now(billingClock);
        if (r.getStatus() == to) {
            return toItem(r);
        }
        if (expireIfDue(r, now)) {
            throw new ConflictException("calls.request.expired");
        }
        boolean allowed = switch (r.getStatus()) {
            case PENDING -> true;
            case DELIVERED -> to != CallRequest.Status.DELIVERED;
            default -> false;
        };
        if (!allowed) {
            throw new ConflictException("calls.request.invalidTransition", r.getStatus().name(), to.name());
        }
        r.setDeviceId(principal.deviceId());
        switch (to) {
            case DELIVERED -> r.setDeliveredAt(now);
            case DIALED -> {
                if (r.getDeliveredAt() == null) {
                    r.setDeliveredAt(now);
                }
                r.setDialedAt(now);
            }
            default -> {
                close(r, CallRequest.Status.CANCELLED, now);
                return toItem(r);
            }
        }
        r.setStatus(to);
        return toItem(requestRepository.save(r));
    }

    /** Fon ishi: muddati o'tgan ochiq so'rovlarni yopadi. */
    @Transactional
    public int expireDue() {
        LocalDateTime now = LocalDateTime.now(billingClock);
        List<CallRequest> due = requestRepository.findOpenExpired(now);
        due.forEach(r -> close(r, CallRequest.Status.EXPIRED, now));
        return due.size();
    }

    // ── Yordamchilar ─────────────────────────────────────────────────────

    private CallRequest visible(Long id, User actor) {
        CallRequest r = requestRepository.findById(id)
            .orElseThrow(() -> CodedException.notFound("calls.request.notFound"));
        boolean mine = r.getUserId().equals(actor.getId()) || actor.getId().equals(r.getCreatedBy());
        if (!mine && !CAN_TARGET_OTHERS.contains(actor.getRole())) {
            throw CodedException.notFound("calls.request.notFound");
        }
        return r;
    }

    /** Muddati o'tgan ochiq so'rov → EXPIRED; qaytaradi — yopildimi. */
    private boolean expireIfDue(CallRequest r, LocalDateTime now) {
        if (r.getStatus().open() && !r.getExpiresAt().isAfter(now)) {
            close(r, CallRequest.Status.EXPIRED, now);
            return true;
        }
        return false;
    }

    private void close(CallRequest r, CallRequest.Status status, LocalDateTime now) {
        r.setStatus(status);
        r.setClosedAt(now);
        requestRepository.save(r);
    }

    private static CallRequest.Status parseAck(String raw) {
        if (raw != null) {
            try {
                CallRequest.Status s = CallRequest.Status.valueOf(raw.trim().toUpperCase(Locale.ROOT));
                if (s == CallRequest.Status.DELIVERED || s == CallRequest.Status.DIALED
                        || s == CallRequest.Status.CANCELLED) {
                    return s;
                }
            } catch (IllegalArgumentException ignored) {
                // pastda
            }
        }
        throw CodedException.badRequest("calls.request.ackInvalid");
    }

    private CallDtos.RequestItem toItem(CallRequest r) {
        String contact = null;
        if (r.getLeadId() != null) {
            contact = leadRepository.findById(r.getLeadId()).map(Lead::getFullName).orElse(null);
        } else if (r.getStudentId() != null) {
            contact = studentRepository.findById(r.getStudentId()).map(CallContactMatcher::studentName).orElse(null);
        }
        User by = r.getCreatedBy() != null ? userRepository.findById(r.getCreatedBy()).orElse(null) : null;
        return toItem(r, contact, by);
    }

    private static CallDtos.RequestItem toItem(CallRequest r, String contact, User by) {
        return new CallDtos.RequestItem(r.getId(), r.getUserId(), r.getPhone(), r.getStatus().name(),
            r.getLeadId() != null ? new CallDtos.Ref(r.getLeadId(), contact) : null,
            r.getStudentId() != null ? new CallDtos.Ref(r.getStudentId(), contact) : null,
            contact, CallDtos.UserRef.of(by), r.getCreatedAt(), r.getExpiresAt(), r.getDeliveredAt(), r.getDialedAt(),
            r.getClosedAt());
    }

    private static void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }
}
