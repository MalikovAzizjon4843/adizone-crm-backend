package com.crm.service;

import com.crm.dto.request.SalaryRuleRequest;
import com.crm.dto.response.SalaryRuleResponse;
import com.crm.entity.SalaryRule;
import com.crm.entity.User;
import com.crm.entity.enums.PayrollStatus;
import com.crm.entity.enums.UserRole;
import com.crm.exception.CodedException;
import com.crm.exception.ConflictException;
import com.crm.exception.ResourceNotFoundException;
import com.crm.repository.PayrollRepository;
import com.crm.repository.SalaryRuleRepository;
import com.crm.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Oylik qoidalari (faqat SUPER_ADMIN) — docs/design/payroll-v2.md §7.
 *
 * <p>Maydonlar rolga bog'liq: TEACHER — {@code fixedSalary, perPayingStudent, substituteLessonRate};
 * SALES_MANAGER, SALES_HEAD — {@code fixedSalary, perNewStudent}; ADMIN — {@code fixedSalary, perNewStudent,
 * kpiThreshold, kpiBonus}. Rolga tegishli bo'lmagan maydon nol emas bo'lsa 400 — hisobda
 * baribir ishlatilmaydi va "saqlandi, lekin ta'sir qilmadi" holati bo'lmasin.
 */
@Service
@RequiredArgsConstructor
public class SalaryRuleService {

    public static final Set<UserRole> SALARY_ROLES =
        EnumSet.of(UserRole.TEACHER, UserRole.ADMIN, UserRole.SALES_MANAGER, UserRole.SALES_HEAD);

    private static final Set<PayrollStatus> USED_STATUSES = EnumSet.of(PayrollStatus.APPROVED, PayrollStatus.PAID);

    private final SalaryRuleRepository salaryRuleRepository;
    private final UserRepository userRepository;
    private final PayrollRepository payrollRepository;

    @Transactional(readOnly = true)
    public List<SalaryRuleResponse> getAll() {
        return salaryRuleRepository.findAllByOrderByRoleAscIdAsc().stream()
            .map(this::toResponse)
            .toList();
    }

    @Transactional(readOnly = true)
    public SalaryRuleResponse getById(Long id) {
        return toResponse(find(id));
    }

    /**
     * Yangi qoida. {@code effectiveFrom} berilsa, shu xodim (yoki shu rolning umumiy) oldingi faol
     * qoidasi {@code effectiveFrom − 1 kun} da yopiladi (§11 #3) — tarix "yangi qoida" bilan yuritiladi.
     * Shu doirada {@code effectiveFrom ≥ yangi.effectiveFrom} bo'lgan faol qoida esa yopilmaydi:
     * APPROVED/PAID oylikda ishlatilmagan bo'lsa nofaol qilinadi ({@code isActive=false, effectiveTo=null}),
     * ishlatilgan bo'lsa — 409 {@code salaryRule.overlapsUsed} (hech narsa yozilmaydi).
     */
    @Transactional
    public SalaryRuleResponse create(SalaryRuleRequest request) {
        SalaryRule rule = new SalaryRule();
        apply(rule, request);
        if (rule.getEffectiveFrom() != null) {
            // Shu sanadan yoki keyin boshlanadigan faol qoidalar: yopish effectiveTo < effectiveFrom
            // qilardi — o'rniga ishlatilmagan bo'lsa nofaol qilinadi, ishlatilgan bo'lsa 409.
            List<SalaryRule> sameOrLater = rule.getUser() != null
                ? salaryRuleRepository.findActivePersonalFromOnOrAfter(rule.getUser().getId(), rule.getEffectiveFrom())
                : salaryRuleRepository.findActiveRoleFromOnOrAfter(rule.getRole(), rule.getEffectiveFrom());
            for (SalaryRule other : sameOrLater) {
                if (payrollRepository.existsBySalaryRuleIdAndStatusIn(other.getId(), USED_STATUSES)) {
                    throw new ConflictException("salaryRule.overlapsUsed", other.getId(), other.getEffectiveFrom());
                }
            }
            for (SalaryRule other : sameOrLater) {
                other.setIsActive(false);
                other.setEffectiveTo(null);
                salaryRuleRepository.save(other);
            }
            List<SalaryRule> open = rule.getUser() != null
                ? salaryRuleRepository.findOpenPersonalBefore(rule.getUser().getId(), rule.getEffectiveFrom())
                : salaryRuleRepository.findOpenRoleBefore(rule.getRole(), rule.getEffectiveFrom());
            for (SalaryRule previous : open) {
                previous.setEffectiveTo(rule.getEffectiveFrom().minusDays(1));
                salaryRuleRepository.save(previous);
            }
        }
        return toResponse(salaryRuleRepository.save(rule));
    }

    /**
     * Tahrir. Qoida tasdiqlangan yoki to'langan oylikda ishlatilgan bo'lsa — 409
     * {@code salaryRule.inUse}: o'zgarish o'tgan oyliklarni "jim" o'zgartirmasin, yangi qoida yaratilsin.
     */
    @Transactional
    public SalaryRuleResponse update(Long id, SalaryRuleRequest request) {
        SalaryRule rule = find(id);
        if (payrollRepository.existsBySalaryRuleIdAndStatusIn(id, USED_STATUSES)) {
            throw new ConflictException("salaryRule.inUse", id);
        }
        apply(rule, request);
        return toResponse(salaryRuleRepository.save(rule));
    }

    @Transactional
    public void delete(Long id) {
        SalaryRule rule = find(id);
        rule.setIsActive(false);
        salaryRuleRepository.save(rule);
    }

    private SalaryRule find(Long id) {
        return salaryRuleRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("SalaryRule", id));
    }

    private void apply(SalaryRule rule, SalaryRuleRequest request) {
        if (!request.getUnknownFields().isEmpty()) {
            throw CodedException.badRequest("salaryRule.field.unknown", String.join(", ", request.getUnknownFields()));
        }
        UserRole role = request.getRole();
        if (!SALARY_ROLES.contains(role)) {
            throw CodedException.badRequest("salaryRule.role.invalid", role);
        }
        validateApplicable(role, request);

        rule.setRole(role);
        if (request.getUserId() != null) {
            User user = userRepository.findById(request.getUserId())
                .orElseThrow(() -> new ResourceNotFoundException("User", request.getUserId()));
            if (user.getRole() != role) {
                throw CodedException.badRequest("salaryRule.userRoleMismatch", user.getRole(), role);
            }
            rule.setUser(user);
        } else {
            rule.setUser(null);
        }
        rule.setFixedSalary(nz(request.getFixedSalary()));
        rule.setPerPayingStudent(nz(request.getPerPayingStudent()));
        rule.setPerNewStudent(nz(request.getPerNewStudent()));
        rule.setKpiThreshold(request.getKpiThreshold());
        rule.setKpiBonus(nz(request.getKpiBonus()));
        rule.setSubstituteLessonRate(role == UserRole.TEACHER ? request.getSubstituteLessonRate() : null);
        rule.setIsActive(request.getIsActive() == null || request.getIsActive());
        if (request.getEffectiveFrom() != null && request.getEffectiveTo() != null
                && request.getEffectiveTo().isBefore(request.getEffectiveFrom())) {
            throw CodedException.badRequest("salaryRule.effectiveRange.invalid");
        }
        rule.setEffectiveFrom(request.getEffectiveFrom());
        rule.setEffectiveTo(request.getEffectiveTo());
    }

    private static void validateApplicable(UserRole role, SalaryRuleRequest r) {
        List<String> wrong = new ArrayList<>();
        if (role != UserRole.TEACHER && positive(r.getPerPayingStudent())) {
            wrong.add("perPayingStudent");
        }
        if (role == UserRole.TEACHER && positive(r.getPerNewStudent())) {
            wrong.add("perNewStudent");
        }
        if (role != UserRole.TEACHER && positive(r.getSubstituteLessonRate())) {
            wrong.add("substituteLessonRate");
        }
        if (role != UserRole.ADMIN) {
            if (r.getKpiThreshold() != null && r.getKpiThreshold() > 0) {
                wrong.add("kpiThreshold");
            }
            if (positive(r.getKpiBonus())) {
                wrong.add("kpiBonus");
            }
        }
        if (!wrong.isEmpty()) {
            throw CodedException.badRequest("salaryRule.field.notApplicable", String.join(", ", wrong), role);
        }
    }

    private SalaryRuleResponse toResponse(SalaryRule r) {
        return SalaryRuleResponse.builder()
            .id(r.getId())
            .role(r.getRole())
            .userId(r.getUser() != null ? r.getUser().getId() : null)
            .userName(r.getUser() != null
                ? ((r.getUser().getFirstName() != null ? r.getUser().getFirstName() : "")
                    + " " + (r.getUser().getLastName() != null ? r.getUser().getLastName() : "")).trim()
                : null)
            .fixedSalary(r.getFixedSalary())
            .perPayingStudent(r.getPerPayingStudent())
            .perNewStudent(r.getPerNewStudent())
            .kpiThreshold(r.getKpiThreshold())
            .kpiBonus(r.getKpiBonus())
            .substituteLessonRate(r.getSubstituteLessonRate())
            .isActive(r.getIsActive())
            .effectiveFrom(r.getEffectiveFrom())
            .effectiveTo(r.getEffectiveTo())
            .createdAt(r.getCreatedAt())
            .updatedAt(r.getUpdatedAt())
            .build();
    }

    private static boolean positive(BigDecimal v) {
        return v != null && v.signum() > 0;
    }

    private static BigDecimal nz(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }
}
