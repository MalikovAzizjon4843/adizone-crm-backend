package com.crm.miniapp;

import com.crm.audit.AuditAction;
import com.crm.audit.Audited;
import com.crm.entity.AppIdentity;
import com.crm.entity.AppIdentityStudent;
import com.crm.entity.AppLinkAttempt;
import com.crm.entity.AppLinkRequest;
import com.crm.entity.Student;
import com.crm.entity.TelegramOutbox;
import com.crm.entity.User;
import com.crm.exception.CodedException;
import com.crm.repository.AppIdentityRepository;
import com.crm.repository.AppLinkAttemptRepository;
import com.crm.repository.AppLinkRequestRepository;
import com.crm.repository.StudentRepository;
import com.crm.repository.UserRepository;
import com.crm.service.TeacherAccessService;
import com.crm.telegram.TelegramInitDataValidator;
import com.crm.telegram.TelegramOutboxService;
import com.crm.telegram.TelegramProperties;
import com.crm.telegram.TelegramUpdateHandler;
import com.crm.util.PhoneUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Qo'lda raqam bilan ulash (docs/design/telegram-platform.md §11.1). Kontakt orqali ulash o'zgarmaydi; bu yo'lda
 * telefon Telegram tomonidan tasdiqlanmagan — shuning uchun so'rov markaz xodimi tasdig'idan o'tadi.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MiniAppLinkRequestService {

    /** 24 soatda qo'lda urinishlar (so'rov + topilmadi) chegarasi. */
    static final int DAILY_LIMIT = 5;
    static final int REASON_MAX = 500;

    private final TelegramInitDataValidator initDataValidator;
    private final MiniAppLinkService linkService;
    private final AppLinkRequestRepository requestRepository;
    private final AppLinkAttemptRepository attemptRepository;
    private final AppIdentityRepository identityRepository;
    private final StudentRepository studentRepository;
    private final UserRepository userRepository;
    private final TeacherAccessService teacherAccessService;
    private final TelegramOutboxService outboxService;
    private final TelegramProperties telegramProperties;
    private final Clock billingClock;

    // ── Mini App: so'rov yuborish ────────────────────────────────────────

    /**
     * {@code noRollbackFor}: 404 da ham urinish yozilishi kerak (aks holda limit hech qachon to'lmasdi).
     */
    @Transactional(noRollbackFor = CodedException.class)
    public AppDtos.LinkRequestResult submit(String initData, String rawPhone) {
        TelegramInitDataValidator.WebAppUser user = initDataValidator.validate(initData);
        LocalDateTime now = LocalDateTime.now(billingClock);

        boolean linked = identityRepository.findByTelegramUserId(user.id()).filter(AppIdentity::isActive).isPresent();
        if (linked) {
            throw new CodedException(HttpStatus.CONFLICT, "app.link.alreadyLinked");
        }
        long attempts = attemptRepository.countByTelegramUserIdAndResultInAndCreatedAtAfter(user.id(),
            EnumSet.of(AppLinkAttempt.Result.MANUAL_REQUEST, AppLinkAttempt.Result.MANUAL_NOT_FOUND),
            now.minusHours(24));
        if (attempts >= DAILY_LIMIT) {
            throw new CodedException(HttpStatus.TOO_MANY_REQUESTS, "app.link.rateLimited", DAILY_LIMIT);
        }

        String canonical = PhoneUtils.canonical(rawPhone);
        MiniAppLinkService.Match match = linkService.match(canonical);
        if (match.isEmpty()) {
            attempt(user.id(), AppLinkAttempt.Result.MANUAL_NOT_FOUND, now);
            throw CodedException.notFound("app.phoneNotFound");
        }

        AppLinkRequest pending = requestRepository
            .findFirstByTelegramUserIdAndStatusOrderByIdDesc(user.id(), AppLinkRequest.Status.PENDING).orElse(null);
        if (pending != null && pending.getPhoneCanonical().equals(canonical)) {
            return result(pending);
        }
        if (pending != null) {
            pending.setStatus(AppLinkRequest.Status.CANCELLED);
            pending.setDecidedAt(now);
        }
        AppLinkRequest request = requestRepository.save(AppLinkRequest.builder()
            .telegramUserId(user.id())
            // Shaxsiy chatda chat.id == user.id (Mini App bot orqali ochilgan — chat bor)
            .chatId(user.id())
            .telegramUsername(user.username())
            .firstName(user.firstName())
            .phoneCanonical(canonical)
            .status(AppLinkRequest.Status.PENDING)
            .matchSummary(summary(match))
            .createdAt(now)
            .build());
        attempt(user.id(), AppLinkAttempt.Result.MANUAL_REQUEST, now);
        log.info("Mini App qo'lda ulash so'rovi: request={}, telegramUserId={}", request.getId(), user.id());
        return result(request);
    }

    // ── CRM: ko'rib chiqish ──────────────────────────────────────────────

    /** {@code status} berilmasa — PENDING (eskilari oldin); {@code ALL} — hammasi (yangilari oldin). */
    @Transactional(readOnly = true)
    public List<AppDtos.LinkRequestRow> list(String status) {
        List<AppLinkRequest> rows;
        if (status == null || status.isBlank()) {
            rows = requestRepository.findByStatusOrderByCreatedAtAscIdAsc(AppLinkRequest.Status.PENDING);
        } else if ("ALL".equalsIgnoreCase(status.trim())) {
            rows = requestRepository.findAllByOrderByCreatedAtDescIdDesc();
        } else {
            AppLinkRequest.Status parsed;
            try {
                parsed = AppLinkRequest.Status.valueOf(status.trim().toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw CodedException.badRequest("appLinkRequest.status.invalid", status);
            }
            rows = requestRepository.findByStatusOrderByCreatedAtAscIdAsc(parsed);
        }
        Map<Long, User> deciders = userRepository.findAllById(rows.stream().map(AppLinkRequest::getDecidedBy)
                .filter(java.util.Objects::nonNull).distinct().toList()).stream()
            .collect(Collectors.toMap(User::getId, Function.identity()));
        return rows.stream().map(r -> row(r, deciders.get(r.getDecidedBy()))).toList();
    }

    @Transactional
    @Audited(action = AuditAction.UPDATE, entity = "AppLinkRequest", entityId = "#id",
        summary = "'Telegram ilova: qo''lda ulash so''rovi tasdiqlandi'")
    public AppDtos.LinkRequestRow approve(Long id) {
        AppLinkRequest request = pendingOrThrow(id);
        MiniAppLinkService.Match match = linkService.match(request.getPhoneCanonical());
        if (match.isEmpty()) {
            throw new CodedException(HttpStatus.CONFLICT, "appLinkRequest.noMatch");
        }
        MiniAppLinkService.LinkResult linked = linkService.linkVerified(request.getTelegramUserId(),
            request.getChatId(), request.getTelegramUsername(), request.getFirstName(), request.getPhoneCanonical(),
            match);
        User actor = teacherAccessService.getCurrentUserOrThrow();
        request.setStatus(AppLinkRequest.Status.APPROVED);
        request.setDecidedBy(actor.getId());
        request.setDecidedAt(LocalDateTime.now(billingClock));
        request.setIdentityId(linked.identity().getId());
        request.setMatchSummary(summary(match));

        if (request.getChatId() != null) {
            outboxService.enqueue(request.getChatId(),
                "✅ So'rovingiz tasdiqlandi, hisob ulandi.\n\n" + TelegramUpdateHandler.studentsLine(linked),
                TelegramOutboxService.openAppButton(TelegramUpdateHandler.BTN_OPEN_APP, telegramProperties.getWebappUrl()),
                TelegramOutbox.Priority.NORMAL, "linkreq:" + request.getId() + ":approved", "LINK_APPROVED");
        }
        return row(request, actor);
    }

    @Transactional
    @Audited(action = AuditAction.UPDATE, entity = "AppLinkRequest", entityId = "#id",
        summary = "'Telegram ilova: qo''lda ulash so''rovi rad etildi'")
    public AppDtos.LinkRequestRow reject(Long id, String reason) {
        String text = reason == null ? "" : reason.trim();
        if (text.isEmpty()) {
            throw CodedException.badRequest("appLinkRequest.reason.required");
        }
        if (text.length() > REASON_MAX) {
            throw CodedException.badRequest("appLinkRequest.reason.tooLong", REASON_MAX);
        }
        AppLinkRequest request = pendingOrThrow(id);
        User actor = teacherAccessService.getCurrentUserOrThrow();
        request.setStatus(AppLinkRequest.Status.REJECTED);
        request.setDecidedBy(actor.getId());
        request.setDecidedAt(LocalDateTime.now(billingClock));
        request.setRejectReason(text);

        if (request.getChatId() != null) {
            outboxService.enqueue(request.getChatId(),
                "❌ Hisobni ulash so'rovi rad etildi.\nSabab: " + TelegramUpdateHandler.escape(text),
                null, TelegramOutbox.Priority.NORMAL, "linkreq:" + request.getId() + ":rejected", "LINK_REJECTED");
        }
        return row(request, actor);
    }

    private AppLinkRequest pendingOrThrow(Long id) {
        AppLinkRequest request = requestRepository.findById(id)
            .orElseThrow(() -> CodedException.notFound("appLinkRequest.notFound"));
        if (request.getStatus() != AppLinkRequest.Status.PENDING) {
            throw new CodedException(HttpStatus.CONFLICT, "appLinkRequest.notPending", request.getStatus().name());
        }
        return request;
    }

    /** "O'quvchi: Ali Karimov; Farzand: Vali Karimov; O'qituvchi: Madina Rahimova". */
    private String summary(MiniAppLinkService.Match match) {
        Map<Long, Student> students = studentRepository.findAllById(match.students().keySet()).stream()
            .collect(Collectors.toMap(Student::getId, Function.identity()));
        List<String> self = new ArrayList<>();
        List<String> children = new ArrayList<>();
        match.students().forEach((id, relation) -> {
            Student s = students.get(id);
            if (s != null) {
                (relation == AppIdentityStudent.Relation.SELF ? self : children)
                    .add(s.getFirstName() + " " + s.getLastName());
            }
        });
        List<String> parts = new ArrayList<>();
        if (!self.isEmpty()) {
            parts.add("O'quvchi: " + String.join(", ", self));
        }
        if (!children.isEmpty()) {
            parts.add("Farzand: " + String.join(", ", children));
        }
        if (match.staffUserId() != null) {
            userRepository.findById(match.staffUserId())
                .ifPresent(u -> parts.add("O'qituvchi: " + (u.getFirstName() + " " + u.getLastName()).trim()));
        }
        String text = String.join("; ", parts);
        return text.length() > 1000 ? text.substring(0, 997) + "..." : text;
    }

    private void attempt(long telegramUserId, AppLinkAttempt.Result result, LocalDateTime now) {
        attemptRepository.save(AppLinkAttempt.builder()
            .telegramUserId(telegramUserId)
            .result(result)
            .createdAt(now)
            .build());
    }

    private static AppDtos.LinkRequestResult result(AppLinkRequest r) {
        return new AppDtos.LinkRequestResult(r.getId(), r.getStatus().name(), r.getCreatedAt());
    }

    private static AppDtos.LinkRequestRow row(AppLinkRequest r, User decider) {
        return new AppDtos.LinkRequestRow(r.getId(), r.getStatus().name(), r.getPhoneCanonical(),
            r.getTelegramUserId(), r.getTelegramUsername(), r.getFirstName(), r.getMatchSummary(), r.getCreatedAt(),
            r.getDecidedAt(), decider != null ? (decider.getFirstName() + " " + decider.getLastName()).trim() : null,
            r.getRejectReason(), r.getIdentityId());
    }
}
