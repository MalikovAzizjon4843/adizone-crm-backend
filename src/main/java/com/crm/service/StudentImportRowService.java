package com.crm.service;

import com.crm.entity.Group;
import com.crm.entity.Parent;
import com.crm.entity.Student;
import com.crm.entity.StudentGroup;
import com.crm.entity.StudentParent;
import com.crm.entity.User;
import com.crm.entity.enums.MarketingSource;
import com.crm.entity.enums.PaymentStatus;
import com.crm.entity.enums.PaymentType;
import com.crm.entity.enums.StudentStatus;
import com.crm.repository.GroupRepository;
import com.crm.repository.ParentRepository;
import com.crm.repository.StudentGroupRepository;
import com.crm.repository.StudentParentRepository;
import com.crm.repository.StudentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Har bir Excel qatorini alohida tranzaksiyada saqlaydi (REQUIRES_NEW).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class StudentImportRowService {

    private final StudentRepository studentRepository;
    private final GroupRepository groupRepository;
    private final StudentGroupRepository studentGroupRepository;
    private final ParentRepository parentRepository;
    private final StudentParentRepository studentParentRepository;
    private final StudentService studentService;
    private final PaymentScheduleService paymentScheduleService;

    public record RowImportOutcome(List<String> warnings) {}

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public RowImportOutcome importRow(StudentRowData data, User currentUser) {
        List<String> warnings = new ArrayList<>();

        if (data.firstName() == null || data.firstName().isBlank()) {
            throw new RowImportException("Ism kiritilmagan");
        }
        if (data.lastName() == null || data.lastName().isBlank()) {
            throw new RowImportException("Familiya kiritilmagan");
        }
        if (data.phone() == null || data.phone().isBlank()) {
            throw new RowImportException("Telefon kiritilmagan");
        }
        if (studentRepository.findByPhone(data.phone()).isPresent()) {
            throw new RowImportException("Telefon takrorlangan: " + data.phone());
        }

        boolean isTrial = Boolean.TRUE.equals(data.isTrial());

        Student student = new Student();
        student.setFirstName(data.firstName());
        student.setLastName(data.lastName());
        student.setPhone(data.phone());
        student.setParentPhone(data.parentPhone());
        student.setBirthDate(data.birthDate());
        student.setGender(data.gender());
        student.setMarketingSource(data.marketingSource());
        student.setAddress(data.address());
        student.setAdmissionDate(data.admissionDate() != null ? data.admissionDate() : LocalDate.now());
        student.setNotes(data.notes());
        student.setStatus(StudentStatus.ACTIVE);
        // "Sinov darsi" = HA bo'lsa, guruhga yozilmasa ham TRIAL bo'lib qolsin.
        student.setPaymentStatus(isTrial ? PaymentStatus.TRIAL : PaymentStatus.PENDING);
        student.setAdmissionNumber(studentService.generateNextAdmissionNumber());
        if (currentUser != null) {
            student.setCreatedBy(currentUser);
            student.setAttributedUserId(currentUser.getId());
        }

        Student saved = studentRepository.save(student);

        // Guruh ImportService validatsiyasida aniqlangan; bu yerda faqat qayta tekshiriladi.
        // Muammo bo'lsa xato tashlanadi — guruhsiz o'quvchi YARATILMAYDI (tranzaksiya rollback).
        if (data.groupId() != null) {
            Group group = groupRepository.findById(data.groupId())
                .orElseThrow(() -> new RowImportException(
                    "'" + data.groupName() + "' nomli guruh topilmadi"));
            long current = studentGroupRepository.countByGroupIdAndIsActiveTrue(group.getId());
            if (group.getMaxStudents() != null && current >= group.getMaxStudents()) {
                throw new RowImportException("'" + group.getGroupName() + "' guruhi to'lgan ("
                    + current + "/" + group.getMaxStudents() + ")");
            }
            enrollInGroup(saved, group, data);
        }

        linkParent(saved, data.fatherFullName(), data.fatherPhone(), data.fatherAddress(),
            "FATHER", true);
        linkParent(saved, data.motherFullName(), data.motherPhone(), data.motherAddress(),
            "MOTHER", saved.getParentPhone() == null || saved.getParentPhone().isBlank());

        if ((saved.getParentPhone() == null || saved.getParentPhone().isBlank())
            && data.parentPhone() != null && !data.parentPhone().isBlank()) {
            saved.setParentPhone(data.parentPhone());
            studentRepository.save(saved);
        }

        return new RowImportOutcome(warnings);
    }

    private void enrollInGroup(Student student, Group group, StudentRowData data) {
        LocalDate joinDate = data.admissionDate() != null ? data.admissionDate() : LocalDate.now();
        LocalDate paymentStart = data.paymentStartDate() != null
            ? data.paymentStartDate() : LocalDate.now();
        boolean isTrial = Boolean.TRUE.equals(data.isTrial());

        BigDecimal courseMonthly = group.getCourse() != null
            ? group.getCourse().getMonthlyPrice() : null;
        BigDecimal monthlyFee = data.monthlyFee() != null
            ? data.monthlyFee()
            : (courseMonthly != null ? courseMonthly : BigDecimal.ZERO);
        BigDecimal lessonPrice = data.lessonPrice();

        PaymentType paymentType = data.paymentType() != null ? data.paymentType() : PaymentType.MONTHLY;
        String initialStatus = isTrial ? "TRIAL" : "PENDING";

        student.setPaymentStartDate(paymentStart);
        student.setMonthlyFee(monthlyFee);
        student.setPaymentStatus(isTrial ? PaymentStatus.TRIAL : PaymentStatus.PENDING);
        studentRepository.save(student);

        StudentGroup sg = StudentGroup.builder()
            .student(student)
            .group(group)
            .joinDate(joinDate)
            .paymentStartDate(paymentStart)
            .nextPaymentDate(paymentStart)
            .isTrial(isTrial)
            .isActive(true)
            .monthlyPriceOverride(monthlyFee)
            .paymentType(paymentType)
            .lessonPrice(lessonPrice)
            .paymentStatus(initialStatus)
            .lessonsAttended(0)
            .build();
        studentGroupRepository.save(sg);
        studentGroupRepository.flush();
        paymentScheduleService.recalculateForStudent(student);
    }

    private void linkParent(Student student, String fullName, String phone, String address,
                            String relation, boolean preferPrimary) {
        boolean hasName = fullName != null && !fullName.isBlank();
        boolean hasPhone = phone != null && !phone.isBlank();
        if (!hasName && !hasPhone) {
            return;
        }

        String resolvedPhone = hasPhone ? truncate(phone.trim(), 20) : null;
        String resolvedName = hasName ? truncate(fullName.trim(), 200) : "Ota-ona";

        Parent parent = resolvedPhone != null
            ? parentRepository.findByPhone(resolvedPhone).orElse(null)
            : null;

        if (parent == null) {
            parent = Parent.builder()
                .fullName(resolvedName)
                .phone(resolvedPhone)
                .address(address)
                .relation(relation)
                .isActive(true)
                .build();
        } else {
            parent.setFullName(resolvedName);
            if (address != null && !address.isBlank()) {
                parent.setAddress(address);
            }
            parent.setRelation(relation);
            parent.setIsActive(true);
        }
        parent = parentRepository.save(parent);

        if (!studentParentRepository.existsByStudentIdAndParentId(student.getId(), parent.getId())) {
            studentParentRepository.save(StudentParent.builder()
                .student(student)
                .parent(parent)
                .relation(relation)
                .isPrimary(preferPrimary)
                .build());
        }

        if (resolvedPhone != null
            && (preferPrimary || student.getParentPhone() == null || student.getParentPhone().isBlank())) {
            student.setParentPhone(resolvedPhone);
            studentRepository.save(student);
        }
    }

    /** Excel qatoridan o'qilgan, validatsiyadan oldingi ma'lumot. */
    public record StudentRowData(
        String firstName,
        String lastName,
        String phone,
        String parentPhone,
        LocalDate birthDate,
        String gender,
        MarketingSource marketingSource,
        String address,
        LocalDate admissionDate,
        String notes,
        /** Faqat xato/ogohlantirish matnlari uchun — qidiruv groupId bo'yicha ketadi. */
        String groupName,
        /** ImportService validatsiyasida topilgan guruh; null bo'lsa o'quvchi guruhsiz yaratiladi. */
        Long groupId,
        PaymentType paymentType,
        BigDecimal monthlyFee,
        BigDecimal lessonPrice,
        LocalDate paymentStartDate,
        Boolean isTrial,
        String fatherFullName,
        String fatherPhone,
        String fatherAddress,
        String motherFullName,
        String motherPhone,
        String motherAddress
    ) {}

    public static class RowImportException extends RuntimeException {
        public RowImportException(String message) {
            super(message);
        }
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) {
            return null;
        }
        return s.length() <= maxLen ? s : s.substring(0, maxLen);
    }
}
