package com.crm.calls;

import com.crm.billing.DebtorService;
import com.crm.entity.Lead;
import com.crm.entity.Parent;
import com.crm.entity.PhoneCall;
import com.crm.entity.Student;
import com.crm.entity.StudentGroup;
import com.crm.entity.Task;
import com.crm.entity.User;
import com.crm.entity.enums.StudentStatus;
import com.crm.entity.enums.UserRole;
import com.crm.exception.CodedException;
import com.crm.miniapp.MiniAppLinkService;
import com.crm.repository.LeadRepository;
import com.crm.repository.ParentRepository;
import com.crm.repository.PhoneCallRepository;
import com.crm.repository.StudentGroupRepository;
import com.crm.repository.StudentParentRepository;
import com.crm.repository.StudentRepository;
import com.crm.repository.TaskRepository;
import com.crm.repository.UserRepository;
import com.crm.service.LeadStageService;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Kiruvchi qo'ng'iroq kartasi — {@code GET /api/calls/app/lookup?phone=} (docs/design/calls.md §4.4). Telefon jiringlab
 * turganida ko'rsatiladi: tez bo'lishi kerak (< 200 ms) — natija {@code app.calls.lookup-cache-seconds} keshda
 * (kalit: xodim + raqam), sinxron / natija shu raqam keshini tozalaydi.
 *
 * <p>Ruxsat: SALES_MANAGER boshqa operatorning lidini faqat "kimniki" darajasida ko'radi ({@code restricted});
 * qarz summasi faqat SUPER_ADMIN / ADMIN (qolganlarga {@code debtor} va {@code debtDays}).
 */
@Service
public class CallLookupService {

    private final CallContactMatcher matcher;
    private final LeadRepository leadRepository;
    private final StudentRepository studentRepository;
    private final ParentRepository parentRepository;
    private final StudentParentRepository studentParentRepository;
    private final StudentGroupRepository studentGroupRepository;
    private final TaskRepository taskRepository;
    private final PhoneCallRepository callRepository;
    private final UserRepository userRepository;
    private final LeadStageService leadStageService;
    private final DebtorService debtorService;
    private final Clock billingClock;
    private final TransactionTemplate tx;
    private final Cache<String, CallDtos.LookupResponse> cache;

    public CallLookupService(CallContactMatcher matcher, LeadRepository leadRepository,
                             StudentRepository studentRepository, ParentRepository parentRepository,
                             StudentParentRepository studentParentRepository,
                             StudentGroupRepository studentGroupRepository, TaskRepository taskRepository,
                             PhoneCallRepository callRepository, UserRepository userRepository,
                             LeadStageService leadStageService, DebtorService debtorService, Clock billingClock,
                             TransactionTemplate tx, CallsProperties properties) {
        this.matcher = matcher;
        this.leadRepository = leadRepository;
        this.studentRepository = studentRepository;
        this.parentRepository = parentRepository;
        this.studentParentRepository = studentParentRepository;
        this.studentGroupRepository = studentGroupRepository;
        this.taskRepository = taskRepository;
        this.callRepository = callRepository;
        this.userRepository = userRepository;
        this.leadStageService = leadStageService;
        this.debtorService = debtorService;
        this.billingClock = billingClock;
        this.tx = tx;
        this.cache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(Math.max(1, properties.getLookupCacheSeconds())))
            .maximumSize(10_000)
            .build();
    }

    public CallDtos.LookupResponse lookup(CallDevicePrincipal principal, String rawPhone) {
        String normalized = CallContactMatcher.normalize(rawPhone);
        if (normalized == null) {
            throw CodedException.badRequest("calls.phone.invalid");
        }
        return cache.get(principal.userId() + "|" + normalized, k -> tx.execute(s -> build(principal.userId(), normalized)));
    }

    /** Shu raqam bo'yicha barcha xodimlar keshi. */
    public void evict(String normalized) {
        if (normalized == null) {
            return;
        }
        String suffix = "|" + normalized;
        cache.asMap().keySet().removeIf(k -> k.endsWith(suffix));
    }

    private CallDtos.LookupResponse build(Long userId, String normalized) {
        User viewer = userRepository.findById(userId)
            .orElseThrow(() -> CodedException.forbidden("calls.device.userInactive"));
        boolean amounts = viewer.getRole() == UserRole.SUPER_ADMIN || viewer.getRole() == UserRole.ADMIN;
        String canonical = CallContactMatcher.canonical(normalized);
        CallContactMatcher.Match match = matcher.match(canonical);
        LocalDate today = LocalDate.now(billingClock);

        return switch (match.type()) {
            case LEAD -> leadCard(viewer, normalized, match);
            case STUDENT -> {
                Student s = studentRepository.findById(match.studentId()).orElseThrow();
                CallDtos.StudentCard card = studentCards(List.of(s), amounts, today).get(0);
                yield new CallDtos.LookupResponse(normalized, "STUDENT", card.name(), false, null, card, null, null,
                    lastCall(normalized), openStudentTask(List.of(s.getId())), "/students/" + s.getId());
            }
            case PARENT -> {
                List<Student> children = childrenOf(canonical);
                List<CallDtos.StudentCard> cards = studentCards(children, amounts, today);
                CallDtos.Ref parent = match.parentId() != null
                    ? parentRepository.findById(match.parentId()).map(p -> new CallDtos.Ref(p.getId(), p.getFullName()))
                        .orElse(null)
                    : null;
                Long first = children.isEmpty() ? null : children.get(0).getId();
                yield new CallDtos.LookupResponse(normalized, "PARENT", match.name(), false, null, null, cards, parent,
                    lastCall(normalized), children.isEmpty() ? null
                        : openStudentTask(children.stream().map(Student::getId).toList()),
                    first != null ? "/students/" + first : null);
            }
            case UNKNOWN -> new CallDtos.LookupResponse(normalized, "UNKNOWN", null, false, null, null, null, null,
                lastCall(normalized), null, null);
        };
    }

    private CallDtos.LookupResponse leadCard(User viewer, String normalized, CallContactMatcher.Match match) {
        Lead lead = leadRepository.findById(match.leadId()).orElseThrow();
        User owner = lead.getAssignedUser();
        boolean restricted = viewer.getRole() == UserRole.SALES_MANAGER
            && (owner == null || !owner.getId().equals(viewer.getId()));
        if (restricted) {
            return new CallDtos.LookupResponse(normalized, "LEAD", lead.getFullName(), true,
                new CallDtos.LeadCard(null, null, null, null, CallDtos.UserRef.of(owner), null),
                null, null, null, null, null, null);
        }
        CallDtos.LeadCard card = new CallDtos.LeadCard(lead.getId(), lead.getStatus(),
            leadStageService.label(lead.getStatus()), lead.getSource(), CallDtos.UserRef.of(owner), lead.getCreatedAt());
        Task task = taskRepository.findOpenByLeadIds(List.of(lead.getId())).stream().findFirst().orElse(null);
        return new CallDtos.LookupResponse(normalized, "LEAD", lead.getFullName(), false, card, null, null, null,
            lastCall(normalized), taskRef(task), "/leads?lead=" + lead.getId());
    }

    private List<Student> childrenOf(String canonical) {
        List<String> variants = MiniAppLinkService.variants(canonical);
        Map<Long, Student> out = new LinkedHashMap<>();
        List<Long> parentIds = parentRepository.findByPhoneInAndIsActiveTrue(variants).stream().map(Parent::getId)
            .toList();
        if (!parentIds.isEmpty()) {
            studentParentRepository.findStudentsByParentIds(parentIds, StudentStatus.ARCHIVED)
                .forEach(s -> out.putIfAbsent(s.getId(), s));
        }
        studentRepository.findByParentPhoneInAndStatusNot(variants, StudentStatus.ARCHIVED)
            .forEach(s -> out.putIfAbsent(s.getId(), s));
        return new ArrayList<>(out.values());
    }

    private List<CallDtos.StudentCard> studentCards(List<Student> students, boolean amounts, LocalDate today) {
        if (students.isEmpty()) {
            return List.of();
        }
        Map<Long, DebtorService.DebtFlag> flags = debtorService.debtFlags(null,
            students.stream().map(Student::getId).toList(), today);
        List<CallDtos.StudentCard> out = new ArrayList<>();
        for (Student s : students) {
            List<CallDtos.GroupRef> groups = studentGroupRepository.findActiveByStudentId(s.getId()).stream()
                .map(StudentGroup::getGroup).filter(Objects::nonNull)
                .map(g -> new CallDtos.GroupRef(g.getId(), g.getGroupName()))
                .toList();
            DebtorService.DebtFlag f = flags.getOrDefault(s.getId(), DebtorService.DebtFlag.NONE);
            out.add(new CallDtos.StudentCard(s.getId(), CallContactMatcher.studentName(s),
                s.getStatus() != null ? s.getStatus().name() : null, groups, f.studentDebtor(),
                f.studentDebtor() ? f.studentDebtDays() : null,
                amounts && f.studentDebtor() ? f.studentDebtAmount() : null));
        }
        return out;
    }

    private CallDtos.LastCall lastCall(String normalized) {
        List<PhoneCall> noted = callRepository.findLastNoted(normalized, PageRequest.of(0, 1));
        PhoneCall c = !noted.isEmpty() ? noted.get(0)
            : callRepository.findLast(normalized, PageRequest.of(0, 1)).stream().findFirst().orElse(null);
        if (c == null) {
            return null;
        }
        User by = userRepository.findById(c.getResultBy() != null ? c.getResultBy() : c.getUserId()).orElse(null);
        return new CallDtos.LastCall(c.getId(), c.getStartedAt(), c.getDirection().name(),
            c.getResultCode() != null ? c.getResultCode().name() : null, c.getNote(), CallDtos.UserRef.of(by));
    }

    private CallDtos.TaskRef openStudentTask(List<Long> studentIds) {
        return taskRef(taskRepository.findOpenByStudentIds(studentIds).stream().findFirst().orElse(null));
    }

    private static CallDtos.TaskRef taskRef(Task t) {
        return t == null ? null
            : new CallDtos.TaskRef(t.getId(), t.getTitle(), t.getDueAt(), CallDtos.UserRef.of(t.getAssignedTo()));
    }
}
