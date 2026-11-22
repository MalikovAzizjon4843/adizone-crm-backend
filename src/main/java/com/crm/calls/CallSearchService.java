package com.crm.calls;

import com.crm.entity.Lead;
import com.crm.entity.Parent;
import com.crm.entity.Student;
import com.crm.entity.User;
import com.crm.entity.enums.StudentStatus;
import com.crm.entity.enums.UserRole;
import com.crm.exception.CodedException;
import com.crm.repository.StudentParentRepository;
import com.crm.repository.UserRepository;
import com.crm.service.LeadStageService;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Terish ekrani qidiruvi — {@code GET /api/calls/app/search?q=} (docs/design/calls.md §4.7): lid / o'quvchi /
 * ota-ona ism yoki telefon bo'yicha. {@code q} — kamida 2 belgi; natija ko'pi bilan {@value #LIMIT} ta.
 *
 * <p>Telefon qidiruvi — {@code q} da harf bo'lmasa: raqamlari (kamida 2) telefon ustunining ichida. O'zbekiston
 * raqami ({@code +998 90 123 45 67}, {@code 901234567}) bazada turlicha yozilgani uchun 9 dan ortiq raqamda oxirgi 9
 * tasi qidiriladi.
 *
 * <p>Ruxsat — {@link CallLookupService} bilan bir xil: SALES_MANAGER boshqa operatorning lidini faqat "kimniki"
 * darajasida ko'radi ({@code restricted}: id, telefon, bosqich yo'q). Arxivlangan o'quvchi chiqmaydi (moslashuv kabi).
 * Tartib: ochiq lidlar → o'quvchilar → ota-onalar → yopiq lidlar.
 */
@Service
@RequiredArgsConstructor
public class CallSearchService {

    static final int LIMIT = 20;
    static final int MIN_LENGTH = 2;

    private final EntityManager em;
    private final UserRepository userRepository;
    private final StudentParentRepository studentParentRepository;
    private final LeadStageService leadStageService;

    @Transactional(readOnly = true)
    public List<CallDtos.SearchItem> search(CallDevicePrincipal principal, String rawQuery) {
        String q = rawQuery == null ? "" : rawQuery.trim();
        if (q.length() < MIN_LENGTH) {
            throw CodedException.badRequest("calls.search.tooShort", MIN_LENGTH);
        }
        if (q.length() > 100) {
            q = q.substring(0, 100);
        }
        User viewer = userRepository.findById(principal.userId())
            .orElseThrow(() -> CodedException.forbidden("calls.device.userInactive"));
        boolean phoneQuery = !q.matches(".*\\p{L}.*");
        String digits = q.replaceAll("[^0-9]", "");
        if (phoneQuery && digits.length() < MIN_LENGTH) {
            throw CodedException.badRequest("calls.search.tooShort", MIN_LENGTH);
        }
        if (digits.length() > 9) {
            digits = digits.substring(digits.length() - 9);
        }
        String pattern = phoneQuery ? "%" + digits + "%" : "%" + escape(q.toLowerCase(Locale.ROOT)) + "%";

        List<Lead> leads = phoneQuery
            ? em.createQuery("""
                    SELECT l FROM Lead l LEFT JOIN FETCH l.assignedUser
                    WHERE l.phone LIKE :p
                    ORDER BY l.createdAt DESC, l.id DESC
                    """, Lead.class).setParameter("p", pattern).setMaxResults(LIMIT).getResultList()
            : em.createQuery("""
                    SELECT l FROM Lead l LEFT JOIN FETCH l.assignedUser
                    WHERE LOWER(l.fullName) LIKE :p ESCAPE '\\'
                    ORDER BY l.createdAt DESC, l.id DESC
                    """, Lead.class).setParameter("p", pattern).setMaxResults(LIMIT).getResultList();
        List<Student> students = phoneQuery
            ? em.createQuery("""
                    SELECT s FROM Student s WHERE s.status <> :archived AND s.phone LIKE :p
                    ORDER BY s.id DESC
                    """, Student.class).setParameter("archived", StudentStatus.ARCHIVED).setParameter("p", pattern)
                .setMaxResults(LIMIT).getResultList()
            : em.createQuery("""
                    SELECT s FROM Student s WHERE s.status <> :archived
                      AND LOWER(CONCAT(COALESCE(s.firstName, ''), ' ', COALESCE(s.lastName, ''))) LIKE :p ESCAPE '\\'
                    ORDER BY s.id DESC
                    """, Student.class).setParameter("archived", StudentStatus.ARCHIVED).setParameter("p", pattern)
                .setMaxResults(LIMIT).getResultList();
        List<Parent> parents = phoneQuery
            ? em.createQuery("""
                    SELECT p FROM Parent p WHERE p.isActive = true AND p.phone LIKE :p ORDER BY p.id DESC
                    """, Parent.class).setParameter("p", pattern).setMaxResults(LIMIT).getResultList()
            : em.createQuery("""
                    SELECT p FROM Parent p WHERE p.isActive = true AND LOWER(p.fullName) LIKE :p ESCAPE '\\'
                    ORDER BY p.id DESC
                    """, Parent.class).setParameter("p", pattern).setMaxResults(LIMIT).getResultList();
        // Ota-ona faqat students.parent_phone da (parents jadvalida yo'q) — telefon bilan
        List<Student> byParentPhone = phoneQuery
            ? em.createQuery("""
                    SELECT s FROM Student s WHERE s.status <> :archived AND s.parentPhone LIKE :p ORDER BY s.id DESC
                    """, Student.class).setParameter("archived", StudentStatus.ARCHIVED).setParameter("p", pattern)
                .setMaxResults(LIMIT).getResultList()
            : List.of();

        List<CallDtos.SearchItem> open = new ArrayList<>();
        List<CallDtos.SearchItem> closed = new ArrayList<>();
        for (Lead l : leads) {
            (leadStageService.isClosed(l.getStatus()) ? closed : open).add(leadItem(viewer, l));
        }
        List<CallDtos.SearchItem> out = new ArrayList<>(open);
        for (Student s : students) {
            out.add(new CallDtos.SearchItem("STUDENT", null, s.getId(), null, CallContactMatcher.studentName(s),
                s.getPhone(), s.getStatus() != null ? s.getStatus().name() : null, false, null));
        }
        Set<String> parentPhones = new HashSet<>();
        for (Parent p : parents) {
            List<Student> children = studentParentRepository.findStudentsByParentIds(List.of(p.getId()),
                StudentStatus.ARCHIVED);
            Student child = children.stream().min(Comparator.comparing(Student::getId)).orElse(null);
            if (p.getPhone() != null) {
                parentPhones.add(p.getPhone());
            }
            out.add(new CallDtos.SearchItem("PARENT", null, child != null ? child.getId() : null, p.getId(),
                p.getFullName(), p.getPhone(), child != null ? CallContactMatcher.studentName(child) : null, false,
                null));
        }
        for (Student child : byParentPhone) {
            if (child.getParentPhone() == null || !parentPhones.add(child.getParentPhone())) {
                continue;
            }
            out.add(new CallDtos.SearchItem("PARENT", null, child.getId(), null,
                CallContactMatcher.studentName(child) + " (ota-ona)", child.getParentPhone(),
                CallContactMatcher.studentName(child), false, null));
        }
        out.addAll(closed);
        return out.size() > LIMIT ? List.copyOf(out.subList(0, LIMIT)) : out;
    }

    private CallDtos.SearchItem leadItem(User viewer, Lead lead) {
        User owner = lead.getAssignedUser();
        boolean restricted = viewer.getRole() == UserRole.SALES_MANAGER
            && (owner == null || !owner.getId().equals(viewer.getId()));
        if (restricted) {
            return new CallDtos.SearchItem("LEAD", null, null, null, lead.getFullName(), null, null, true,
                CallDtos.UserRef.of(owner));
        }
        return new CallDtos.SearchItem("LEAD", lead.getId(), null, null, lead.getFullName(), lead.getPhone(),
            leadStageService.label(lead.getStatus()), false, CallDtos.UserRef.of(owner));
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
