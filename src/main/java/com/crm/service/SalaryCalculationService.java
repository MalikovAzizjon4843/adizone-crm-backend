package com.crm.service;

import com.crm.dto.response.BonusPenaltyPreviewDto;
import com.crm.dto.response.SalaryCalculationDto;
import com.crm.entity.SalaryRule;
import com.crm.entity.Teacher;
import com.crm.entity.User;
import com.crm.entity.enums.StudentStatus;
import com.crm.entity.enums.UserRole;
import com.crm.exception.BadRequestException;
import com.crm.exception.ResourceNotFoundException;
import com.crm.repository.PaymentRepository;
import com.crm.repository.SalaryRuleRepository;
import com.crm.repository.StudentRepository;
import com.crm.repository.TeacherRepository;
import com.crm.repository.UserRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class SalaryCalculationService {

    private static final Set<UserRole> SALARY_ROLES = EnumSet.of(
        UserRole.TEACHER, UserRole.ADMINISTRATOR, UserRole.SALES_MANAGER);

    private final UserRepository userRepository;
    private final TeacherRepository teacherRepository;
    private final SalaryRuleRepository salaryRuleRepository;
    private final PaymentRepository paymentRepository;
    private final StudentRepository studentRepository;
    private final BonusPenaltyService bonusPenaltyService;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public List<SalaryCalculationDto> calculateAll(int month, int year) {
        validatePeriod(month, year);
        List<SalaryCalculationDto> out = new ArrayList<>();
        for (User user : userRepository.findByIsActiveTrue()) {
            if (user.getRole() == null || !SALARY_ROLES.contains(user.getRole())) {
                continue;
            }
            out.add(calculate(user, month, year, false));
        }
        return out;
    }

    @Transactional(readOnly = true)
    public SalaryCalculationDto calculateForUser(Long userId, int month, int year) {
        validatePeriod(month, year);
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new ResourceNotFoundException("User", userId));
        return calculate(user, month, year, true);
    }

    @Transactional(readOnly = true)
    public SalaryCalculationDto calculate(User user, int month, int year, boolean withDetails) {
        YearMonth ym = YearMonth.of(year, month);
        LocalDate from = ym.atDay(1);
        LocalDate to = ym.atEndOfMonth();
        LocalDate asOf = to;

        Optional<SalaryRule> ruleOpt = salaryRuleRepository.resolveRule(user, asOf);
        if (ruleOpt.isEmpty()) {
            return SalaryCalculationDto.builder()
                .userId(user.getId())
                .fullName(fullName(user))
                .role(user.getRole() != null ? user.getRole().name() : null)
                .month(month)
                .year(year)
                .calculable(false)
                .message("Oylik qoidasi topilmadi")
                .totalAmount(null)
                .build();
        }

        SalaryRule rule = ruleOpt.get();
        UserRole role = user.getRole();

        if (role == UserRole.TEACHER) {
            return calculateTeacher(user, rule, month, year, from, to, withDetails);
        }
        if (role == UserRole.ADMINISTRATOR) {
            return calculateAdministrator(user, rule, month, year, from, to, withDetails);
        }
        if (role == UserRole.SALES_MANAGER) {
            return calculateSales(user, rule, month, year, from, to, withDetails);
        }

        return SalaryCalculationDto.builder()
            .userId(user.getId())
            .fullName(fullName(user))
            .role(role != null ? role.name() : null)
            .month(month)
            .year(year)
            .calculable(false)
            .message("Bu rol uchun oylik hisoblanmaydi")
            .build();
    }

    public String toDetailsJson(SalaryCalculationDto dto) {
        try {
            Map<String, Object> details = dto.getDetails() != null
                ? new LinkedHashMap<>(dto.getDetails())
                : new LinkedHashMap<>();
            return objectMapper.writeValueAsString(details);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    private SalaryCalculationDto calculateTeacher(
            User user, SalaryRule rule, int month, int year,
            LocalDate from, LocalDate to, boolean withDetails) {

        Teacher teacher = teacherRepository.findByUser_Id(user.getId())
            .orElse(null);
        if (teacher == null) {
            return SalaryCalculationDto.builder()
                .userId(user.getId())
                .fullName(fullName(user))
                .role(UserRole.TEACHER.name())
                .month(month)
                .year(year)
                .calculable(false)
                .message("O'qituvchi profili bog'lanmagan")
                .build();
        }

        List<Object[]> pairs = paymentRepository.findPaidStudentGroupPairsForTeacher(
            teacher.getId(), from, to);
        List<SalaryCalculationDto.StudentDetailItem> students = new ArrayList<>();
        for (Object[] row : pairs) {
            students.add(SalaryCalculationDto.StudentDetailItem.builder()
                .studentId(toLong(row[0]))
                .name(row[1] != null ? row[1].toString() : null)
                .groupId(toLong(row[2]))
                .groupName(row[3] != null ? row[3].toString() : null)
                .paymentDate(toLocalDate(row[4]))
                .type("PAID")
                .build());
        }

        int paidCount = students.size();
        BigDecimal fee = nz(rule.getPerStudentFee());
        BigDecimal perStudentAmount = fee.multiply(BigDecimal.valueOf(paidCount));
        BigDecimal base = nz(rule.getBaseSalary());

        BonusPenaltyPreviewDto bp = bonusPenaltyService.previewForTeacher(teacher.getId(), to);
        BigDecimal adjustment = bp.getNet() != null ? bp.getNet() : BigDecimal.ZERO;
        BigDecimal total = base.add(perStudentAmount).add(adjustment);

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("paidStudents", students.stream().map(s -> Map.of(
            "studentId", s.getStudentId() != null ? s.getStudentId() : 0,
            "name", s.getName() != null ? s.getName() : "",
            "groupName", s.getGroupName() != null ? s.getGroupName() : "",
            "paymentDate", s.getPaymentDate() != null ? s.getPaymentDate().toString() : ""
        )).toList());

        return SalaryCalculationDto.builder()
            .userId(user.getId())
            .fullName(fullName(user))
            .role(UserRole.TEACHER.name())
            .month(month)
            .year(year)
            .baseSalary(base)
            .paidStudentCount(paidCount)
            .perStudentAmount(perStudentAmount)
            .newStudentCount(0)
            .newStudentAmount(BigDecimal.ZERO)
            .kpiApplied(false)
            .kpiAmount(BigDecimal.ZERO)
            .totalActiveStudents(null)
            .bonusPenaltyAdjustment(adjustment)
            .totalAmount(total)
            .calculable(true)
            .details(withDetails ? details : null)
            .students(withDetails ? students : null)
            .build();
    }

    private SalaryCalculationDto calculateAdministrator(
            User user, SalaryRule rule, int month, int year,
            LocalDate from, LocalDate to, boolean withDetails) {

        List<Object[]> newRows = paymentRepository.findNewStudentsByAttributedUser(
            user.getId(), from, to);
        List<SalaryCalculationDto.StudentDetailItem> students = mapNewStudents(newRows);

        BigDecimal base = nz(rule.getBaseSalary());
        int newCount = students.size();
        BigDecimal newBonus = nz(rule.getNewStudentBonus()).multiply(BigDecimal.valueOf(newCount));

        long totalActive = studentRepository.countByStatus(StudentStatus.ACTIVE);
        boolean kpiApplied = rule.getKpiThreshold() != null
            && totalActive >= rule.getKpiThreshold();
        BigDecimal kpiAmount = kpiApplied ? nz(rule.getKpiBonus()) : BigDecimal.ZERO;

        BigDecimal total = base.add(newBonus).add(kpiAmount);

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("newStudents", students.stream().map(s -> Map.of(
            "studentId", s.getStudentId() != null ? s.getStudentId() : 0,
            "name", s.getName() != null ? s.getName() : "",
            "firstPaymentDate", s.getPaymentDate() != null ? s.getPaymentDate().toString() : ""
        )).toList());
        details.put("kpi", Map.of(
            "threshold", rule.getKpiThreshold() != null ? rule.getKpiThreshold() : 0,
            "actual", totalActive,
            "applied", kpiApplied
        ));

        return SalaryCalculationDto.builder()
            .userId(user.getId())
            .fullName(fullName(user))
            .role(UserRole.ADMINISTRATOR.name())
            .month(month)
            .year(year)
            .baseSalary(base)
            .paidStudentCount(null)
            .perStudentAmount(null)
            .newStudentCount(newCount)
            .newStudentAmount(newBonus)
            .kpiApplied(kpiApplied)
            .kpiAmount(kpiAmount)
            .totalActiveStudents((int) totalActive)
            .bonusPenaltyAdjustment(BigDecimal.ZERO)
            .totalAmount(total)
            .calculable(true)
            .details(withDetails ? details : null)
            .students(withDetails ? students : null)
            .build();
    }

    private SalaryCalculationDto calculateSales(
            User user, SalaryRule rule, int month, int year,
            LocalDate from, LocalDate to, boolean withDetails) {

        List<Object[]> newRows = paymentRepository.findNewStudentsByAttributedUser(
            user.getId(), from, to);
        List<SalaryCalculationDto.StudentDetailItem> students = mapNewStudents(newRows);

        BigDecimal base = nz(rule.getBaseSalary());
        int newCount = students.size();
        BigDecimal newBonus = nz(rule.getNewStudentBonus()).multiply(BigDecimal.valueOf(newCount));
        BigDecimal total = base.add(newBonus);

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("newStudents", students.stream().map(s -> Map.of(
            "studentId", s.getStudentId() != null ? s.getStudentId() : 0,
            "name", s.getName() != null ? s.getName() : "",
            "firstPaymentDate", s.getPaymentDate() != null ? s.getPaymentDate().toString() : ""
        )).toList());

        return SalaryCalculationDto.builder()
            .userId(user.getId())
            .fullName(fullName(user))
            .role(UserRole.SALES_MANAGER.name())
            .month(month)
            .year(year)
            .baseSalary(base)
            .paidStudentCount(null)
            .perStudentAmount(null)
            .newStudentCount(newCount)
            .newStudentAmount(newBonus)
            .kpiApplied(false)
            .kpiAmount(BigDecimal.ZERO)
            .totalActiveStudents(null)
            .bonusPenaltyAdjustment(BigDecimal.ZERO)
            .totalAmount(total)
            .calculable(true)
            .details(withDetails ? details : null)
            .students(withDetails ? students : null)
            .build();
    }

    private static List<SalaryCalculationDto.StudentDetailItem> mapNewStudents(List<Object[]> rows) {
        List<SalaryCalculationDto.StudentDetailItem> students = new ArrayList<>();
        for (Object[] row : rows) {
            students.add(SalaryCalculationDto.StudentDetailItem.builder()
                .studentId(toLong(row[0]))
                .name(row[1] != null ? row[1].toString() : null)
                .paymentDate(toLocalDate(row[2]))
                .type("NEW")
                .build());
        }
        return students;
    }

    private static void validatePeriod(int month, int year) {
        if (month < 1 || month > 12) {
            throw new BadRequestException("Oy 1–12 oralig'ida bo'lishi kerak");
        }
        if (year < 2000 || year > 2100) {
            throw new BadRequestException("Noto'g'ri yil");
        }
    }

    private static String fullName(User u) {
        return ((u.getFirstName() != null ? u.getFirstName() : "")
            + " " + (u.getLastName() != null ? u.getLastName() : "")).trim();
    }

    private static BigDecimal nz(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }

    private static Long toLong(Object v) {
        return v == null ? null : ((Number) v).longValue();
    }

    private static LocalDate toLocalDate(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof LocalDate ld) {
            return ld;
        }
        if (v instanceof java.sql.Date sd) {
            return sd.toLocalDate();
        }
        return LocalDate.parse(v.toString().substring(0, 10));
    }
}
