package com.crm.service;

import com.crm.billing.BillingAuth;
import com.crm.billing.BonusLedgerService;
import com.crm.billing.Money;
import com.crm.billing.PaymentPlanner;
import com.crm.exception.CodedException;
import com.crm.repository.StudentGroupRepository;

import com.crm.dto.request.BonusPenaltyCreateDto;
import com.crm.dto.response.BonusPenaltyDto;
import com.crm.dto.response.BonusPenaltyPreviewDto;
import com.crm.dto.response.BonusPenaltySummaryDto;
import com.crm.dto.response.PageResponse;
import com.crm.entity.BonusPenalty;
import com.crm.entity.Student;
import com.crm.entity.Teacher;
import com.crm.entity.User;
import com.crm.entity.enums.BonusPenaltyKind;
import com.crm.entity.enums.BonusPenaltyStatus;
import com.crm.entity.enums.BonusTargetType;
import com.crm.entity.enums.UserRole;
import com.crm.exception.BadRequestException;
import com.crm.exception.ResourceNotFoundException;
import com.crm.repository.BonusPenaltyRepository;
import com.crm.repository.StudentRepository;
import com.crm.repository.TeacherRepository;
import com.crm.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class BonusPenaltyService {

    private final BonusPenaltyRepository bonusPenaltyRepository;
    private final StudentRepository studentRepository;
    private final TeacherRepository teacherRepository;
    private final UserRepository userRepository;
    private final StudentGroupRepository studentGroupRepository;
    private final PaymentPlanner paymentPlanner;
    private final BonusLedgerService bonusLedgerService;

    @Transactional(readOnly = true)
    public PageResponse<BonusPenaltyDto> getAll(
            String kind,
            String targetType,
            Long studentId,
            Long teacherId,
            Long userId,
            String status,
            Pageable pageable) {

        Specification<BonusPenalty> spec = buildSpec(
            parseKind(kind),
            parseTargetType(targetType),
            studentId,
            teacherId,
            userId,
            parseStatus(status));

        Page<BonusPenalty> page = bonusPenaltyRepository.findAll(spec, pageable);
        return PageResponse.<BonusPenaltyDto>builder()
            .content(page.getContent().stream().map(this::toDto).toList())
            .pageNumber(page.getNumber())
            .pageSize(page.getSize())
            .totalElements(page.getTotalElements())
            .totalPages(page.getTotalPages())
            .last(page.isLast())
            .build();
    }

    @Transactional(readOnly = true)
    public BonusPenaltyDto getById(Long id) {
        return toDto(findById(id));
    }

    @Transactional(readOnly = true)
    public BonusPenaltySummaryDto getSummary(LocalDate from, LocalDate to) {
        LocalDate start = from != null ? from : LocalDate.now().withDayOfMonth(1);
        LocalDate end = to != null ? to : LocalDate.now();

        Specification<BonusPenalty> spec = (root, query, cb) ->
            cb.between(root.get("effectiveDate"), start, end);
        spec = spec.and((root, query, cb) ->
            cb.notEqual(root.get("status"), BonusPenaltyStatus.CANCELLED));

        List<BonusPenalty> items = bonusPenaltyRepository.findAll(spec);
        BigDecimal totalBonus = BigDecimal.ZERO;
        BigDecimal totalPenalty = BigDecimal.ZERO;

        for (BonusPenalty item : items) {
            if (item.getKind() == BonusPenaltyKind.BONUS) {
                totalBonus = totalBonus.add(item.getAmount());
            } else {
                totalPenalty = totalPenalty.add(item.getAmount());
            }
        }

        return BonusPenaltySummaryDto.builder()
            .totalBonus(totalBonus)
            .totalPenalty(totalPenalty)
            .count(items.size())
            .build();
    }

    /** STAFF (ADMIN/SALES) — oyligiga tushadigan PENDING bonus/jarimalar (payroll-v2 §11 #5). */
    @Transactional(readOnly = true)
    public BonusPenaltyPreviewDto previewForStaff(Long userId, LocalDate upToDate) {
        LocalDate cutoff = upToDate != null ? upToDate : LocalDate.now();
        BigDecimal totalBonus = BigDecimal.ZERO;
        BigDecimal totalPenalty = BigDecimal.ZERO;
        long count = 0;
        for (BonusPenalty bp : bonusPenaltyRepository.findByUser_IdAndStatus(userId, BonusPenaltyStatus.PENDING)) {
            if (bp.getTargetType() != BonusTargetType.STAFF
                    || (bp.getEffectiveDate() != null && bp.getEffectiveDate().isAfter(cutoff))) {
                continue;
            }
            count++;
            if (bp.getKind() == BonusPenaltyKind.BONUS) {
                totalBonus = totalBonus.add(bp.getAmount());
            } else {
                totalPenalty = totalPenalty.add(bp.getAmount());
            }
        }
        return BonusPenaltyPreviewDto.builder()
            .totalBonus(totalBonus)
            .totalPenalty(totalPenalty)
            .net(totalBonus.subtract(totalPenalty))
            .count(count)
            .build();
    }

    @Transactional(readOnly = true)
    public BonusPenaltyPreviewDto previewForTeacher(Long teacherId, LocalDate upToDate) {
        LocalDate cutoff = upToDate != null ? upToDate : LocalDate.now();
        List<BonusPenalty> pending = bonusPenaltyRepository
            .findByTeacherIdAndStatus(teacherId, BonusPenaltyStatus.PENDING);

        BigDecimal totalBonus = BigDecimal.ZERO;
        BigDecimal totalPenalty = BigDecimal.ZERO;
        long count = 0;

        for (BonusPenalty bp : pending) {
            if (bp.getTargetType() != BonusTargetType.TEACHER) {
                continue;
            }
            if (bp.getEffectiveDate() != null && bp.getEffectiveDate().isAfter(cutoff)) {
                continue;
            }
            count++;
            if (bp.getKind() == BonusPenaltyKind.BONUS) {
                totalBonus = totalBonus.add(bp.getAmount());
            } else {
                totalPenalty = totalPenalty.add(bp.getAmount());
            }
        }

        return BonusPenaltyPreviewDto.builder()
            .totalBonus(totalBonus)
            .totalPenalty(totalPenalty)
            .net(totalBonus.subtract(totalPenalty))
            .count(count)
            .build();
    }

    @Transactional(readOnly = true)
    public BonusPenaltyPreviewDto previewForStudent(Long studentId, LocalDate upToDate) {
        LocalDate cutoff = upToDate != null ? upToDate : LocalDate.now();
        List<BonusPenalty> pending = bonusPenaltyRepository
            .findByStudentIdAndStatus(studentId, BonusPenaltyStatus.PENDING);

        BigDecimal totalBonus = BigDecimal.ZERO;
        BigDecimal totalPenalty = BigDecimal.ZERO;
        long count = 0;

        for (BonusPenalty bp : pending) {
            if (bp.getTargetType() != BonusTargetType.STUDENT) {
                continue;
            }
            if (bp.getEffectiveDate() != null && bp.getEffectiveDate().isAfter(cutoff)) {
                continue;
            }
            count++;
            if (bp.getKind() == BonusPenaltyKind.BONUS) {
                totalBonus = totalBonus.add(bp.getAmount());
            } else {
                totalPenalty = totalPenalty.add(bp.getAmount());
            }
        }

        return BonusPenaltyPreviewDto.builder()
            .totalBonus(totalBonus)
            .totalPenalty(totalPenalty)
            .net(totalBonus.subtract(totalPenalty))
            .count(count)
            .build();
    }

    /** To'lovsiz qo'llash (billing v2 §6.5) — ledger'ga BONUS/PENALTY yoziladi. */
    @Transactional
    public BonusPenaltyDto apply(Long id, Long groupId) {
        return toDto(bonusLedgerService.apply(id, groupId));
    }

    @Transactional
    public BonusPenaltyDto create(BonusPenaltyCreateDto dto) {
        BonusPenalty entity = new BonusPenalty();
        applyDto(entity, dto);
        entity.setStatus(BonusPenaltyStatus.PENDING);
        entity.setCreatedBy(currentUser());
        if (entity.getEffectiveDate() == null) {
            entity.setEffectiveDate(LocalDate.now());
        }
        return toDto(bonusPenaltyRepository.save(entity));
    }

    @Transactional
    public BonusPenaltyDto update(Long id, BonusPenaltyCreateDto dto) {
        BonusPenalty entity = findById(id);
        ensurePending(entity, "Faqat kutilayotgan yozuvlar tahrirlanadi");
        applyDto(entity, dto);
        return toDto(bonusPenaltyRepository.save(entity));
    }

    /**
     * PENDING → CANCELLED (hozirgidek). APPLIED → CANCELLED (billing v2 §6.5): faqat SUPER_ADMIN,
     * sabab majburiy, ledger'ga REVERSAL.
     */
    @Transactional
    public BonusPenaltyDto cancel(Long id, String reason) {
        BonusPenalty entity = findById(id);
        if (entity.getStatus() == BonusPenaltyStatus.APPLIED) {
            if (!BillingAuth.hasAnyRole("SUPER_ADMIN")) {
                throw CodedException.forbidden("bonus.cancel.forbidden");
            }
            return toDto(bonusLedgerService.cancelApplied(id, reason));
        }
        ensurePending(entity, "Faqat kutilayotgan yozuvlar bekor qilinadi");
        if (reason != null && !reason.isBlank()) {
            entity.setCancelReason(reason.trim());
        }
        entity.setStatus(BonusPenaltyStatus.CANCELLED);
        return toDto(bonusPenaltyRepository.save(entity));
    }

    @Transactional
    public void delete(Long id) {
        BonusPenalty entity = findById(id);
        ensurePending(entity, "Qo'llangan yozuvlar o'chirilmaydi");
        bonusPenaltyRepository.delete(entity);
    }

    private void applyDto(BonusPenalty entity, BonusPenaltyCreateDto dto) {
        if (dto.getKind() == null || dto.getTargetType() == null || dto.getAmount() == null) {
            throw new BadRequestException("kind, targetType va amount majburiy");
        }
        if (dto.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BadRequestException("Summa musbat bo'lishi kerak");
        }
        if (dto.getTargetType() == BonusTargetType.STUDENT && !Money.isWhole(dto.getAmount())) {
            throw CodedException.badRequest("money.wholeSumRequired");
        }

        entity.setKind(dto.getKind());
        entity.setTargetType(dto.getTargetType());
        entity.setAmount(dto.getAmount());
        entity.setReason(dto.getReason());
        if (dto.getEffectiveDate() != null) {
            entity.setEffectiveDate(dto.getEffectiveDate());
        }

        if (dto.getTargetType() == BonusTargetType.STUDENT) {
            if (dto.getStudentId() == null) {
                throw new BadRequestException("STUDENT uchun studentId ko'rsatilishi shart");
            }
            Student student = studentRepository.findById(dto.getStudentId())
                .orElseThrow(() -> new ResourceNotFoundException("Student", dto.getStudentId()));
            entity.setStudent(student);
            entity.setTeacher(null);
            entity.setUser(null);
            entity.setStudentGroupId(dto.getGroupId() != null
                ? paymentPlanner.resolveEnrollment(student.getId(), dto.getGroupId()).getId()
                : null);
        } else if (dto.getTargetType() == BonusTargetType.STAFF) {
            // payroll-v2 §11 #5: ADMIN/SALES oyligiga — o'qituvchi uchun TEACHER turi ishlatiladi
            if (dto.getUserId() == null) {
                throw CodedException.badRequest("bonus.staff.userRequired");
            }
            User user = userRepository.findById(dto.getUserId())
                .orElseThrow(() -> new ResourceNotFoundException("User", dto.getUserId()));
            if (user.getRole() != UserRole.ADMIN && user.getRole() != UserRole.SALES_MANAGER
                    && user.getRole() != UserRole.SALES_HEAD) {
                throw CodedException.badRequest("bonus.staff.roleInvalid", user.getRole());
            }
            entity.setUser(user);
            entity.setStudent(null);
            entity.setTeacher(null);
            entity.setStudentGroupId(null);
        } else {
            if (dto.getTeacherId() == null) {
                throw new BadRequestException("TEACHER uchun teacherId ko'rsatilishi shart");
            }
            Teacher teacher = teacherRepository.findById(dto.getTeacherId())
                .orElseThrow(() -> new ResourceNotFoundException("Teacher", dto.getTeacherId()));
            entity.setTeacher(teacher);
            entity.setStudent(null);
            entity.setUser(null);
        }
    }

    private static void ensurePending(BonusPenalty entity, String message) {
        if (entity.getStatus() != BonusPenaltyStatus.PENDING) {
            throw new BadRequestException(message);
        }
    }

    private static Specification<BonusPenalty> buildSpec(
            BonusPenaltyKind kind,
            BonusTargetType targetType,
            Long studentId,
            Long teacherId,
            Long userId,
            BonusPenaltyStatus status) {

        Specification<BonusPenalty> spec = Specification.where(null);

        if (kind != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("kind"), kind));
        }
        if (targetType != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("targetType"), targetType));
        }
        if (studentId != null) {
            spec = spec.and((root, query, cb) ->
                cb.equal(root.get("student").get("id"), studentId));
        }
        if (userId != null) {
            spec = spec.and((root, query, cb) ->
                cb.equal(root.get("user").get("id"), userId));
        }
        if (teacherId != null) {
            spec = spec.and((root, query, cb) ->
                cb.equal(root.get("teacher").get("id"), teacherId));
        }
        if (status != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("status"), status));
        }
        return spec;
    }

    private static BonusPenaltyKind parseKind(String value) {
        return parseEnum(value, BonusPenaltyKind.class);
    }

    private static BonusTargetType parseTargetType(String value) {
        return parseEnum(value, BonusTargetType.class);
    }

    private static BonusPenaltyStatus parseStatus(String value) {
        return parseEnum(value, BonusPenaltyStatus.class);
    }

    private static <E extends Enum<E>> E parseEnum(String value, Class<E> type) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private BonusPenalty findById(Long id) {
        return bonusPenaltyRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("BonusPenalty", id));
    }

    private User currentUser() {
        String username = SecurityContextHolder.getContext().getAuthentication().getName();
        return userRepository.findByUsername(username).orElse(null);
    }

    private BonusPenaltyDto toDto(BonusPenalty bp) {
        BonusPenaltyDto dto = BonusPenaltyDto.builder()
            .id(bp.getId())
            .uuid(bp.getUuid())
            .kind(bp.getKind())
            .targetType(bp.getTargetType())
            .amount(bp.getAmount())
            .reason(bp.getReason())
            .status(bp.getStatus())
            .effectiveDate(bp.getEffectiveDate())
            .createdAt(bp.getCreatedAt())
            .signedAmount(signedAmount(bp.getKind(), bp.getAmount()))
            .studentGroupId(bp.getStudentGroupId())
            .ledgerTxId(bp.getLedgerTxId())
            .appliedToPaymentId(bp.getAppliedToPaymentId())
            .appliedToPayrollId(bp.getAppliedToPayrollId())
            .cancelReason(bp.getCancelReason())
            .build();
        if (bp.getStudentGroupId() != null) {
            studentGroupRepository.findById(bp.getStudentGroupId())
                .ifPresent(sg -> dto.setGroupId(sg.getGroup() != null ? sg.getGroup().getId() : null));
        }

        if (bp.getStudent() != null) {
            String name = bp.getStudent().getFirstName() + " " + bp.getStudent().getLastName();
            dto.setStudentId(bp.getStudent().getId());
            dto.setStudentName(name);
            if (bp.getTargetType() == BonusTargetType.STUDENT) {
                dto.setTargetName(name);
            }
        }
        if (bp.getTeacher() != null) {
            String name = bp.getTeacher().getFirstName() + " " + bp.getTeacher().getLastName();
            dto.setTeacherId(bp.getTeacher().getId());
            dto.setTeacherName(name);
            if (bp.getTargetType() == BonusTargetType.TEACHER) {
                dto.setTargetName(name);
            }
        }
        if (bp.getUser() != null) {
            String name = (bp.getUser().getFirstName() + " " + bp.getUser().getLastName()).trim();
            dto.setUserId(bp.getUser().getId());
            dto.setUserName(name);
            if (bp.getTargetType() == BonusTargetType.STAFF) {
                dto.setTargetName(name);
            }
        }
        if (bp.getCreatedBy() != null) {
            dto.setCreatedByName(bp.getCreatedBy().getFirstName() + " "
                + bp.getCreatedBy().getLastName());
        }
        return dto;
    }

    private static BigDecimal signedAmount(BonusPenaltyKind kind, BigDecimal amount) {
        if (amount == null) {
            return BigDecimal.ZERO;
        }
        return kind == BonusPenaltyKind.PENALTY ? amount.negate() : amount;
    }

    @Transactional(readOnly = true)
    public List<BonusPenaltyDto> getByTeacher(Long teacherId) {
        return bonusPenaltyRepository.findByTeacherIdOrderByEffectiveDateDesc(teacherId)
            .stream()
            .map(this::toDto)
            .toList();
    }
}
