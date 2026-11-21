package com.crm.calls;

import com.crm.entity.Lead;
import com.crm.entity.Parent;
import com.crm.entity.PhoneCall;
import com.crm.entity.Student;
import com.crm.entity.enums.StudentStatus;
import com.crm.miniapp.MiniAppLinkService;
import com.crm.repository.LeadRepository;
import com.crm.repository.ParentRepository;
import com.crm.repository.StudentParentRepository;
import com.crm.repository.StudentRepository;
import com.crm.repository.TeacherRepository;
import com.crm.repository.UserRepository;
import com.crm.service.LeadStageService;
import com.crm.util.PhoneUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;

/**
 * Telefon → CRM kontakti (docs/design/calls.md §4.1). Mavjud qoidalar qayta ishlatiladi: kanonik shakl —
 * {@link PhoneUtils#canonical}, bazadagi turli yozilishlar — {@link MiniAppLinkService#variants}
 * ({@code +998901234567}, {@code 998901234567}, {@code 901234567}).
 *
 * <p>Ustuvorlik: <b>ochiq lid</b> (sotuv uchun amaliy ish) → <b>o'quvchi</b> (arxivlanmagan, avval ACTIVE) →
 * <b>ota-ona</b> ({@code parents} yoki {@code students.parent_phone}) → <b>yopiq lid</b> (eng oxirgisi) → UNKNOWN.
 * Arxivlangan o'quvchi hisobga olinmaydi — qaytib qo'ng'iroq qilsa yangi lid bo'ladi.
 */
@Component
@RequiredArgsConstructor
public class CallContactMatcher {

    private final LeadRepository leadRepository;
    private final StudentRepository studentRepository;
    private final ParentRepository parentRepository;
    private final StudentParentRepository studentParentRepository;
    private final UserRepository userRepository;
    private final TeacherRepository teacherRepository;
    private final LeadStageService leadStageService;

    public record Match(PhoneCall.ContactType type, Long leadId, Long studentId, Long parentId, String name) {
        public static final Match UNKNOWN = new Match(PhoneCall.ContactType.UNKNOWN, null, null, null, null);

        public CallDtos.ContactRef toRef(boolean autoLeadCreated) {
            return new CallDtos.ContactRef(type.name(), leadId, studentId, parentId, name,
                autoLeadCreated ? Boolean.TRUE : null);
        }
    }

    /**
     * Saqlanadigan ko'rinish: O'zbekiston raqami — {@code +998XXXXXXXXX}; boshqasi — {@code +} va raqamlar (5–15 ta);
     * raqam yo'q (yashirin raqam) — {@code null}.
     */
    public static String normalize(String raw) {
        String canonical = PhoneUtils.canonical(raw);
        if (canonical != null) {
            return canonical;
        }
        if (raw == null) {
            return null;
        }
        String digits = raw.replaceAll("[^0-9]", "");
        return digits.length() >= 5 && digits.length() <= 15 ? "+" + digits : null;
    }

    /** CRM bilan solishtiriladigan raqam — faqat kanonik O'zbekiston raqami, boshqasida {@code null}. */
    public static String canonical(String normalized) {
        return normalized != null && normalized.startsWith("+998") && normalized.length() == 13 ? normalized : null;
    }

    @Transactional(readOnly = true)
    public Match match(String canonical) {
        if (canonical == null) {
            return Match.UNKNOWN;
        }
        List<String> variants = MiniAppLinkService.variants(canonical);

        List<Lead> leads = leadRepository.findByPhoneInOrderByCreatedAtDescIdDesc(variants);
        for (Lead lead : leads) {
            if (!leadStageService.isClosed(lead.getStatus())) {
                return new Match(PhoneCall.ContactType.LEAD, lead.getId(), null, null, lead.getFullName());
            }
        }

        List<Student> self = studentRepository.findByPhoneInAndStatusNot(variants, StudentStatus.ARCHIVED);
        if (!self.isEmpty()) {
            Student s = self.stream()
                .min(Comparator.comparing((Student x) -> x.getStatus() != StudentStatus.ACTIVE)
                    .thenComparing(Student::getId, Comparator.reverseOrder()))
                .orElseThrow();
            return new Match(PhoneCall.ContactType.STUDENT, null, s.getId(), null, studentName(s));
        }

        List<Parent> parents = parentRepository.findByPhoneInAndIsActiveTrue(variants);
        if (!parents.isEmpty()) {
            Parent parent = parents.get(0);
            List<Student> children = studentParentRepository.findStudentsByParentIds(
                parents.stream().map(Parent::getId).toList(), StudentStatus.ARCHIVED);
            Long childId = children.isEmpty() ? null : children.get(0).getId();
            return new Match(PhoneCall.ContactType.PARENT, null, childId, parent.getId(), parent.getFullName());
        }
        List<Student> byParentPhone = studentRepository.findByParentPhoneInAndStatusNot(variants, StudentStatus.ARCHIVED);
        if (!byParentPhone.isEmpty()) {
            Student child = byParentPhone.get(0);
            return new Match(PhoneCall.ContactType.PARENT, null, child.getId(), null,
                studentName(child) + " (ota-ona)");
        }

        if (!leads.isEmpty()) {
            Lead lead = leads.get(0);
            return new Match(PhoneCall.ContactType.LEAD, lead.getId(), null, null, lead.getFullName());
        }
        return Match.UNKNOWN;
    }

    /** Xodim raqami: faol foydalanuvchi ({@code users.phone}) yoki faol o'qituvchi profili. */
    @Transactional(readOnly = true)
    public boolean isStaff(String canonical) {
        if (canonical == null) {
            return false;
        }
        List<String> variants = MiniAppLinkService.variants(canonical);
        return userRepository.existsActiveByPhoneIn(variants) || teacherRepository.existsActiveByPhoneIn(variants);
    }

    public static String studentName(Student s) {
        return ((s.getFirstName() == null ? "" : s.getFirstName()) + " "
            + (s.getLastName() == null ? "" : s.getLastName())).trim();
    }
}
