package com.crm.calls;

import com.crm.dto.response.PageResponse;
import com.crm.entity.CallRecording;
import com.crm.entity.Lead;
import com.crm.entity.Parent;
import com.crm.entity.PhoneCall;
import com.crm.entity.Student;
import com.crm.entity.User;
import com.crm.entity.enums.UserRole;
import com.crm.exception.CodedException;
import com.crm.exception.ResourceNotFoundException;
import com.crm.repository.CallRecordingRepository;
import com.crm.repository.LeadRepository;
import com.crm.repository.ParentRepository;
import com.crm.repository.PhoneCallRepository;
import com.crm.repository.StudentRepository;
import com.crm.repository.UserRepository;
import com.crm.service.LeadAccessService;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * CRM: qo'ng'iroqlar ro'yxati, lid / o'quvchi kartasidagi qo'ng'iroqlar, statistika (docs/design/calls.md §7).
 *
 * <p>Ruxsat: SUPER_ADMIN, ADMIN, SALES_HEAD — hamma qo'ng'iroqlar; SALES_MANAGER — faqat o'zinikilar (filtr majburiy
 * qo'yiladi; boshqasining qo'ng'irog'i — 404). Lid kartasi qo'shimcha ravishda lidga ruxsatni tekshiradi.
 */
@Service
@RequiredArgsConstructor
public class CallQueryService {

    static final Set<UserRole> FULL_ACCESS = EnumSet.of(UserRole.SUPER_ADMIN, UserRole.ADMIN, UserRole.SALES_HEAD);
    private static final int MAX_PAGE_SIZE = 100;

    private final PhoneCallRepository callRepository;
    private final CallRecordingRepository recordingRepository;
    private final UserRepository userRepository;
    private final LeadRepository leadRepository;
    private final StudentRepository studentRepository;
    private final ParentRepository parentRepository;
    private final LeadAccessService leadAccessService;
    private final Clock billingClock;

    public record Filter(Long userId, LocalDate from, LocalDate to, String direction, String contactType,
                         String phone, Long leadId, Long studentId) {
    }

    @Transactional(readOnly = true)
    public PageResponse<CallDtos.CallItem> list(Filter filter, int page, int size) {
        User viewer = leadAccessService.getCurrentUserOrThrow();
        Long userId = scopeUserId(viewer, filter.userId());
        PhoneCall.Direction direction = null;
        if (filter.direction() != null && !filter.direction().isBlank()) {
            direction = CallSyncService.parseDirection(filter.direction());
            if (direction == null) {
                throw CodedException.badRequest("calls.filter.direction", filter.direction());
            }
        }
        PhoneCall.ContactType contactType = null;
        if (filter.contactType() != null && !filter.contactType().isBlank()) {
            try {
                contactType = PhoneCall.ContactType.valueOf(filter.contactType().trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw CodedException.badRequest("calls.filter.contactType", filter.contactType());
            }
        }
        if (filter.from() != null && filter.to() != null && filter.to().isBefore(filter.from())) {
            throw CodedException.badRequest("calls.filter.dateRange");
        }
        String phone = filter.phone() == null || filter.phone().isBlank() ? null
            : CallContactMatcher.normalize(filter.phone());
        if (filter.phone() != null && !filter.phone().isBlank() && phone == null) {
            throw CodedException.badRequest("calls.phone.invalid");
        }

        Specification<PhoneCall> spec = spec(userId, filter.from(), filter.to(), direction, contactType, phone,
            filter.leadId(), filter.studentId());
        int pageSize = Math.max(1, Math.min(size, MAX_PAGE_SIZE));
        Page<PhoneCall> result = callRepository.findAll(spec, PageRequest.of(Math.max(page, 0), pageSize,
            Sort.by(Sort.Order.desc("startedAt"), Sort.Order.desc("id"))));
        return PageResponse.<CallDtos.CallItem>builder()
            .content(toItems(result.getContent()))
            .pageNumber(result.getNumber())
            .pageSize(result.getSize())
            .totalElements(result.getTotalElements())
            .totalPages(result.getTotalPages())
            .last(result.isLast())
            .build();
    }

    @Transactional(readOnly = true)
    public PageResponse<CallDtos.CallItem> forLead(Long leadId, int page, int size) {
        if (!leadRepository.existsById(leadId)) {
            throw new ResourceNotFoundException("Lead", leadId);
        }
        leadAccessService.assertCanAccessLead(leadId);
        return list(new Filter(null, null, null, null, null, null, leadId, null), page, size);
    }

    @Transactional(readOnly = true)
    public PageResponse<CallDtos.CallItem> forStudent(Long studentId, int page, int size) {
        if (!studentRepository.existsById(studentId)) {
            throw new ResourceNotFoundException("Student", studentId);
        }
        return list(new Filter(null, null, null, null, null, null, null, studentId), page, size);
    }

    /** Bitta qo'ng'iroq — ruxsat bilan (SALES_MANAGER: faqat o'ziniki, aks holda 404). */
    @Transactional(readOnly = true)
    public PhoneCall requireVisible(Long callId) {
        User viewer = leadAccessService.getCurrentUserOrThrow();
        PhoneCall call = callRepository.findById(callId).orElseThrow(() -> CodedException.notFound("calls.notFound"));
        if (!FULL_ACCESS.contains(viewer.getRole()) && !call.getUserId().equals(viewer.getId())) {
            throw CodedException.notFound("calls.notFound");
        }
        return call;
    }

    // ── Statistika ───────────────────────────────────────────────────────

    /**
     * Xodim bo'yicha: jami, kiruvchi (IN), chiquvchi (OUT), javobsiz (MISSED), rad etilgan (REJECTED), suhbat vaqti
     * (IN + OUT davomiyligi) va <b>javobsizga qayta qo'ng'iroq qilinmaganlar</b> — davrdagi MISSED / REJECTED raqamlari
     * (xodim bo'yicha noyob), ulardan keyin shu raqam bilan hech kim (istalgan xodim) chiquvchi qo'ng'iroq qilmagan va
     * javob berilgan kiruvchi bo'lmagan.
     */
    @Transactional(readOnly = true)
    public CallDtos.StatsResponse stats(LocalDate from, LocalDate to) {
        User viewer = leadAccessService.getCurrentUserOrThrow();
        LocalDate today = LocalDate.now(billingClock);
        LocalDate f = from != null ? from : today.minusDays(29);
        LocalDate t = to != null ? to : today;
        if (t.isBefore(f)) {
            throw CodedException.badRequest("calls.filter.dateRange");
        }
        LocalDateTime start = f.atStartOfDay();
        LocalDateTime end = t.plusDays(1).atStartOfDay();
        List<PhoneCall> calls = FULL_ACCESS.contains(viewer.getRole())
            ? callRepository.findByStartedAtGreaterThanEqualAndStartedAtLessThan(start, end)
            : callRepository.findByUserIdAndStartedAtGreaterThanEqualAndStartedAtLessThan(viewer.getId(), start, end);

        // Har xodim + raqam bo'yicha eng oxirgi javobsiz
        Map<Long, Map<String, LocalDateTime>> lastMissed = new HashMap<>();
        for (PhoneCall c : calls) {
            if (c.getDirection().unanswered() && c.getPhoneNormalized() != null) {
                lastMissed.computeIfAbsent(c.getUserId(), k -> new HashMap<>())
                    .merge(c.getPhoneNormalized(), c.getStartedAt(), (a, b) -> a.isAfter(b) ? a : b);
            }
        }
        Set<String> missedPhones = lastMissed.values().stream().flatMap(m -> m.keySet().stream())
            .collect(Collectors.toSet());
        Map<String, LocalDateTime> lastContact = new HashMap<>();
        if (!missedPhones.isEmpty()) {
            for (Object[] row : callRepository.lastContactByPhone(missedPhones)) {
                lastContact.put((String) row[0], (LocalDateTime) row[1]);
            }
        }

        Map<Long, long[]> acc = new LinkedHashMap<>();
        for (PhoneCall c : calls) {
            long[] a = acc.computeIfAbsent(c.getUserId(), k -> new long[7]);
            a[0]++;
            switch (c.getDirection()) {
                case IN -> {
                    a[1]++;
                    a[5] += c.getDurationSec();
                }
                case OUT -> {
                    a[2]++;
                    a[5] += c.getDurationSec();
                }
                case MISSED -> a[3]++;
                case REJECTED -> a[4]++;
            }
        }
        lastMissed.forEach((userId, phones) -> {
            long[] a = acc.computeIfAbsent(userId, k -> new long[7]);
            phones.forEach((phone, missedAt) -> {
                LocalDateTime contact = lastContact.get(phone);
                if (contact == null || !contact.isAfter(missedAt)) {
                    a[6]++;
                }
            });
        });

        Map<Long, User> users = userRepository.findAllById(acc.keySet()).stream()
            .collect(Collectors.toMap(User::getId, Function.identity()));
        List<CallDtos.StatsRow> rows = new ArrayList<>();
        long[] total = new long[7];
        acc.forEach((userId, a) -> {
            CallDtos.UserRef ref = CallDtos.UserRef.of(users.get(userId));
            rows.add(new CallDtos.StatsRow(userId, ref != null ? ref.name() : null, a[0], a[1], a[2], a[3], a[4],
                a[5], a[6]));
            for (int i = 0; i < 7; i++) {
                total[i] += a[i];
            }
        });
        rows.sort(Comparator.comparingLong(CallDtos.StatsRow::total).reversed()
            .thenComparing(CallDtos.StatsRow::userId));
        return new CallDtos.StatsResponse(f, t, rows, new CallDtos.StatsRow(null, null, total[0], total[1], total[2],
            total[3], total[4], total[5], total[6]));
    }

    // ── Yordamchilar ─────────────────────────────────────────────────────

    private Long scopeUserId(User viewer, Long requested) {
        if (FULL_ACCESS.contains(viewer.getRole())) {
            return requested;
        }
        if (viewer.getRole() == UserRole.SALES_MANAGER) {
            return viewer.getId();
        }
        throw CodedException.forbidden("calls.forbidden");
    }

    private static Specification<PhoneCall> spec(Long userId, LocalDate from, LocalDate to,
                                                 PhoneCall.Direction direction, PhoneCall.ContactType contactType,
                                                 String phone, Long leadId, Long studentId) {
        return (root, query, cb) -> {
            List<Predicate> p = new ArrayList<>();
            if (userId != null) {
                p.add(cb.equal(root.get("userId"), userId));
            }
            if (from != null) {
                p.add(cb.greaterThanOrEqualTo(root.get("startedAt"), from.atStartOfDay()));
            }
            if (to != null) {
                p.add(cb.lessThan(root.get("startedAt"), to.plusDays(1).atStartOfDay()));
            }
            if (direction != null) {
                p.add(cb.equal(root.get("direction"), direction));
            }
            if (contactType != null) {
                p.add(cb.equal(root.get("contactType"), contactType));
            }
            if (phone != null) {
                p.add(cb.equal(root.get("phoneNormalized"), phone));
            }
            if (leadId != null) {
                p.add(cb.equal(root.get("leadId"), leadId));
            }
            if (studentId != null) {
                p.add(cb.equal(root.get("studentId"), studentId));
            }
            return cb.and(p.toArray(new Predicate[0]));
        };
    }

    @Transactional(readOnly = true)
    public CallDtos.CallItem toItem(PhoneCall call) {
        return toItems(List.of(call)).get(0);
    }

    @Transactional(readOnly = true)
    public List<CallDtos.CallItem> toItems(List<PhoneCall> calls) {
        if (calls.isEmpty()) {
            return List.of();
        }
        Set<Long> userIds = new java.util.HashSet<>();
        calls.forEach(c -> {
            userIds.add(c.getUserId());
            if (c.getResultBy() != null) {
                userIds.add(c.getResultBy());
            }
        });
        Map<Long, User> users = byId(userRepository.findAllById(userIds), User::getId);
        Map<Long, Lead> leads = byId(leadRepository.findAllById(ids(calls, PhoneCall::getLeadId)), Lead::getId);
        Map<Long, Student> students = byId(studentRepository.findAllById(ids(calls, PhoneCall::getStudentId)),
            Student::getId);
        Map<Long, Parent> parents = byId(parentRepository.findAllById(ids(calls, PhoneCall::getParentId)),
            Parent::getId);
        Map<Long, CallRecording> recordings = byId(
            recordingRepository.findByCallIdIn(calls.stream().map(PhoneCall::getId).toList()), CallRecording::getCallId);

        List<CallDtos.CallItem> out = new ArrayList<>(calls.size());
        for (PhoneCall c : calls) {
            Lead lead = c.getLeadId() != null ? leads.get(c.getLeadId()) : null;
            Student student = c.getStudentId() != null ? students.get(c.getStudentId()) : null;
            Parent parent = c.getParentId() != null ? parents.get(c.getParentId()) : null;
            CallRecording r = recordings.get(c.getId());
            out.add(new CallDtos.CallItem(c.getId(), c.getDirection().name(), c.getPhoneNormalized(), c.getPhoneRaw(),
                c.getStartedAt(), c.getDurationSec(), CallDtos.UserRef.of(users.get(c.getUserId())),
                c.getContactType().name(),
                lead != null ? new CallDtos.Ref(lead.getId(), lead.getFullName()) : null,
                student != null ? new CallDtos.Ref(student.getId(), CallContactMatcher.studentName(student)) : null,
                parent != null ? new CallDtos.Ref(parent.getId(), parent.getFullName()) : null,
                c.isAutoLeadCreated(), c.getResultCode() != null ? c.getResultCode().name() : null, c.getNote(),
                c.getResultBy() != null ? CallDtos.UserRef.of(users.get(c.getResultBy())) : null, c.getResultAt(),
                r == null ? null : new CallDtos.RecordingRef(r.isAvailable(), r.getMime(), r.getSizeBytes(),
                    r.getDurationSec(), r.getUploadedAt(), r.getDeletedAt())));
        }
        return out;
    }

    private static Set<Long> ids(Collection<PhoneCall> calls, Function<PhoneCall, Long> f) {
        return calls.stream().map(f).filter(Objects::nonNull).collect(Collectors.toSet());
    }

    private static <T> Map<Long, T> byId(Collection<T> items, Function<T, Long> id) {
        Map<Long, T> m = new HashMap<>();
        for (T t : items) {
            m.put(id.apply(t), t);
        }
        return m;
    }
}
