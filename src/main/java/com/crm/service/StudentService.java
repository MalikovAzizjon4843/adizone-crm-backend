package com.crm.service;

import com.crm.audit.AuditAction;
import com.crm.audit.Audited;
import com.crm.config.Messages;
import com.crm.dto.request.BalanceAdjustRequest;
import com.crm.dto.request.BalanceTransferRequest;
import com.crm.dto.request.FreezeStudentRequest;
import com.crm.dto.request.StudentParentRequest;
import com.crm.dto.request.StudentRequest;
import com.crm.dto.request.TransferGroupRequest;
import com.crm.dto.request.StudentCreateAndAddRequest;
import com.crm.dto.request.UnfreezeStudentRequest;
import com.crm.dto.response.*;
import com.crm.entity.Course;
import com.crm.entity.Group;
import com.crm.entity.Parent;
import com.crm.entity.Payment;
import com.crm.entity.Student;
import com.crm.entity.StudentGroup;
import com.crm.entity.StudentParent;
import com.crm.entity.StudentStatusHistory;
import com.crm.entity.User;
import com.crm.entity.enums.AttendanceStatus;
import com.crm.entity.enums.MarketingSource;
import com.crm.entity.enums.PaymentStatus;
import com.crm.entity.enums.PaymentType;
import com.crm.entity.enums.StudentStatus;
import com.crm.entity.enums.StudyFormat;
import com.crm.exception.BadRequestException;
import com.crm.billing.BillingSnapshotService;
import com.crm.billing.BillingStatusService;
import com.crm.billing.EnrollmentLifecycleService;
import com.crm.billing.EnrollmentPricing;
import com.crm.exception.DuplicateResourceException;
import com.crm.exception.ResourceNotFoundException;
import com.crm.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class StudentService {

    private final StudentRepository studentRepository;
    private final Messages messages;
    private final StudentParentRepository studentParentRepository;
    private final ParentRepository parentRepository;
    private final StudentGroupRepository studentGroupRepository;
    private final GroupRepository groupRepository;
    private final PaymentRepository paymentRepository;
    private final AttendanceRepository attendanceRepository;
    private final StudentStatusHistoryRepository studentStatusHistoryRepository;
    private final PaymentScheduleService paymentScheduleService;
    private final TeacherAccessService teacherAccessService;
    private final BalanceTransactionService balanceTransactionService;
    private final EnrollmentLifecycleService enrollmentLifecycleService;
    private final BillingStatusService billingStatusService;
    private final BillingSnapshotService billingSnapshotService;

    @Transactional(readOnly = true)
    public PageResponse<StudentResponse> getAllStudents(int page, int size, String search, StudentStatus status) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        Page<Student> studentPage;

        var teacherScope = teacherAccessService.resolveTeacherScope();
        if (teacherScope.isPresent()) {
            Long teacherId = teacherScope.get().getId();
            if (search != null && !search.isBlank()) {
                studentPage = studentRepository.searchDistinctActiveByTeacherId(
                    teacherId, search.trim(), pageable);
            } else if (status != null) {
                studentPage = studentRepository.findDistinctActiveByTeacherIdAndStatus(
                    teacherId, status, pageable);
            } else {
                studentPage = studentRepository.findDistinctActiveByTeacherId(teacherId, pageable);
            }
        } else if (search != null && !search.isBlank()) {
            studentPage = studentRepository.searchStudents(search, pageable);
        } else if (status != null) {
            studentPage = studentRepository.findByStatus(status, pageable);
        } else {
            studentPage = studentRepository.findAll(pageable);
        }

        List<Student> students = studentPage.getContent();
        Map<Long, StudentGroup> currentGroups = loadCurrentGroups(
            students.stream().map(Student::getId).collect(Collectors.toList()));

        List<StudentResponse> content = students.stream()
            .map(s -> toResponse(s, currentGroups.get(s.getId())))
            .collect(Collectors.toList());

        return PageResponse.<StudentResponse>builder()
            .content(content)
            .pageNumber(page)
            .pageSize(size)
            .totalElements(studentPage.getTotalElements())
            .totalPages(studentPage.getTotalPages())
            .last(studentPage.isLast())
            .build();
    }

    @Transactional(readOnly = true)
    public StudentDetailResponse getStudentById(Long id) {
        teacherAccessService.assertOwnsStudent(id);
        Student student = findById(id);
        return toDetailResponse(student);
    }

    @Transactional(readOnly = true)
    public List<StudentResponse> getArchivedStudents() {
        return studentRepository.findArchivedOrFrozenWithDebt().stream()
            .map(this::toResponse)
            .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<StudentDetailResponse.GroupSummary> getStudentGroupHistory(Long studentId) {
        findById(studentId);
        return studentGroupRepository.findByStudentIdOrderByJoinDateDesc(studentId).stream()
            .map(this::toGroupSummary)
            .collect(Collectors.toList());
    }

    @Transactional
    @Audited(action = AuditAction.CREATE, entity = "Student",
        summary = "'Yangi o''quvchi qo''shildi: ' + #result.firstName + ' ' + #result.lastName",
        entityId = "#result.id",
        label = "#result.firstName + ' ' + #result.lastName")
    public StudentResponse createStudent(StudentRequest request) {
        if (studentRepository.findByPhone(request.getPhone()).isPresent()) {
            throw new DuplicateResourceException(
                messages.get("student.phone.duplicate", request.getPhone()));
        }

        Student student = buildFromRequest(new Student(), request, true);

        if (request.getAdmissionNumber() == null || request.getAdmissionNumber().isBlank()) {
            student.setAdmissionNumber(generateNextAdmissionNumber());
        } else {
            student.setAdmissionNumber(request.getAdmissionNumber().trim());
        }

        // Set admission date if not provided:
        if (request.getAdmissionDate() == null) {
            student.setAdmissionDate(LocalDate.now());
        } else {
            student.setAdmissionDate(request.getAdmissionDate());
        }

        // Default status if not provided:
        if (request.getStatus() == null || request.getStatus().isBlank()) {
            student.setStatus(StudentStatus.ACTIVE);
        }


        if (request.getReferralStudentId() != null) {
            Student referral = findById(request.getReferralStudentId());
            student.setReferralStudent(referral);
        }

        Student saved = studentRepository.save(student);
        applyCreatorAttribution(saved, null);
        studentRepository.save(saved);
        syncParents(saved, request.getParents(), request.getParentPhone());
        if (request.getGroupId() != null) {
            addStudentToGroupIfNeeded(saved, request.getGroupId(), request.getStudyFormat());
        }

        return toResponse(findById(saved.getId()));
    }

    @Transactional
    @Audited(action = AuditAction.UPDATE, entity = "Student",
        summary = "'O''quvchi ma''lumoti o''zgartirildi: ' + #result.firstName + ' ' + #result.lastName",
        entityId = "#id",
        label = "#result.firstName + ' ' + #result.lastName")
    public StudentResponse updateStudent(Long id, StudentRequest request) {
        Student student = findById(id);

        studentRepository.findByPhone(request.getPhone())
            .filter(s -> !s.getId().equals(id))
            .ifPresent(s -> {
                throw new DuplicateResourceException(messages.get("student.phone.usedByOther"));
            });

        buildFromRequest(student, request, false);

        if (request.getReferralStudentId() != null) {
            Student referral = findById(request.getReferralStudentId());
            student.setReferralStudent(referral);
        } else {
            student.setReferralStudent(null);
        }

        if (request.getParents() != null || (request.getParentPhone() != null && !request.getParentPhone().isBlank())) {
            syncParents(student, request.getParents(), request.getParentPhone());
        }

        if (request.getGroupId() != null) {
            Group target = groupRepository.findById(request.getGroupId())
                .orElse(null);
            if (target != null) {
                // Guruh almashsa va format berilmagan bo'lsa — yopilayotgan
                // yozuvdan meros olinadi, aks holda u jimgina yo'qolardi.
                StudyFormat format = request.getStudyFormat();
                for (StudentGroup sg : studentGroupRepository.findActiveByStudentId(student.getId())) {
                    if (sg.getGroup().getId().equals(target.getId())) {
                        // O'sha guruh: yangi yozuv ochilmaydi (addStudentToGroupIfNeeded
                        // erta qaytadi), shuning uchun formatni shu yerda yangilaymiz.
                        if (format != null) {
                            sg.setStudyFormat(format);
                            studentGroupRepository.save(sg);
                        }
                        continue;
                    }
                    if (format == null) {
                        format = sg.getStudyFormat();
                    }
                    sg.setIsActive(false);
                    sg.setLeaveDate(LocalDate.now());
                    studentGroupRepository.save(sg);
                }
                addStudentToGroupIfNeeded(student, target.getId(), format);
            }
        }

        studentRepository.save(student);
        return toResponse(findById(student.getId()));
    }

    public String generateNextAdmissionNumber() {
        return generateAdmissionNumber();
    }

    public void syncParentFromPhone(Student student, String parentPhone, String address) {
        if (parentPhone == null || parentPhone.isBlank()) {
            return;
        }
        StudentParentRequest parent = new StudentParentRequest();
        parent.setPhone(parentPhone.trim());
        parent.setAddress(address);
        parent.setRelation("OTHER");
        parent.setIsPrimary(true);
        syncParents(student, List.of(parent), null);
    }

    private String generateAdmissionNumber() {
        int next = 1;
        Optional<String> latest = studentRepository.findLatestAutoAdmissionNumber();
        if (latest.isPresent()) {
            String value = latest.get();
            int dash = value.indexOf('-');
            if (dash >= 0 && dash < value.length() - 1) {
                try {
                    next = Integer.parseInt(value.substring(dash + 1)) + 1;
                } catch (NumberFormatException ignored) {
                    next = (int) studentRepository.count() + 1;
                }
            }
        }
        return String.format("0-%06d", next);
    }

    private void syncParents(Student student, List<StudentParentRequest> parents, String legacyParentPhone) {
        List<StudentParentRequest> items = new ArrayList<>();
        if (parents != null) {
            items.addAll(parents);
        }
        if (items.isEmpty() && legacyParentPhone != null && !legacyParentPhone.isBlank()) {
            StudentParentRequest legacy = new StudentParentRequest();
            legacy.setPhone(legacyParentPhone.trim());
            legacy.setRelation("OTHER");
            legacy.setIsPrimary(true);
            items.add(legacy);
        }
        if (items.isEmpty()) {
            return;
        }

        boolean hasPrimary = items.stream().anyMatch(p -> Boolean.TRUE.equals(p.getIsPrimary()));
        for (int i = 0; i < items.size(); i++) {
            StudentParentRequest pr = items.get(i);
            if (pr.getPhone() == null || pr.getPhone().isBlank()) {
                continue;
            }
            String phone = pr.getPhone().trim();
            String fullName = resolveParentFullName(pr);
            if (fullName.isBlank()) {
                fullName = "Ota-ona";
            }
            String relation = pr.getRelation() != null && !pr.getRelation().isBlank()
                ? pr.getRelation().trim().toUpperCase(Locale.ROOT) : "OTHER";
            boolean isPrimary = Boolean.TRUE.equals(pr.getIsPrimary())
                || (!hasPrimary && i == 0);

            Parent parent = parentRepository.findByPhone(phone).orElse(null);
            if (parent == null) {
                parent = Parent.builder()
                    .fullName(fullName)
                    .phone(phone)
                    .address(pr.getAddress())
                    .relation(relation)
                    .isActive(true)
                    .build();
            } else {
                // telegramChatId bu yerda ATAYLAB o'zgartirilmaydi — StudentParentRequest'da yo'q,
                // ota-onaning bot bilan bog'lanishi o'quvchi saqlanganda yo'qolmasin.
                parent.setFullName(fullName);
                if (pr.getAddress() != null && !pr.getAddress().isBlank()) {
                    parent.setAddress(pr.getAddress());
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
                    .isPrimary(isPrimary)
                    .build());
            }

            if (isPrimary || student.getParentPhone() == null || student.getParentPhone().isBlank()) {
                student.setParentPhone(phone);
            }
        }
        studentRepository.save(student);
    }

    private static String resolveParentFullName(StudentParentRequest pr) {
        if (pr.getFullName() != null && !pr.getFullName().isBlank()) {
            return pr.getFullName().trim();
        }
        String first = pr.getFirstName() != null ? pr.getFirstName().trim() : "";
        String last = pr.getLastName() != null ? pr.getLastName().trim() : "";
        return (first + " " + last).trim();
    }

    /**
     * Billing v2 (§6.8): {@code fromGroupId} bo'lsa — eski SG yopiladi, narx shartlari va
     * balans ({@code TRANSFER_OUT}/{@code TRANSFER_IN}) yangi SG ga ko'chadi, langar uzluksiz.
     * {@code fromGroupId} siz — oddiy qo'shish (kurs narxi, override yo'q).
     */
    @Transactional
    @Audited(action = AuditAction.UPDATE, entity = "Student",
        summary = "'O''quvchi boshqa guruhga ko''chirildi'",
        entityId = "#studentId")
    public StudentDetailResponse transferGroup(Long studentId, TransferGroupRequest request) {
        if (request.getToGroupId() == null) {
            throw new BadRequestException(messages.get("student.transfer.targetRequired"));
        }

        Student student = findById(studentId);
        Group toGroup = groupRepository.findById(request.getToGroupId())
            .orElseThrow(() -> new ResourceNotFoundException(
                messages.get("error.group.notFound", request.getToGroupId())));

        if (studentGroupRepository.existsByStudentIdAndGroupIdAndIsActiveTrue(
                studentId, request.getToGroupId())) {
            throw new BadRequestException(messages.get("student.transfer.alreadyInGroup"));
        }
        long current = studentGroupRepository.countByGroupIdAndIsActiveTrue(toGroup.getId());
        if (toGroup.getMaxStudents() != null && current >= toGroup.getMaxStudents()) {
            throw new BadRequestException(messages.get("group.full", toGroup.getMaxStudents()));
        }

        String reason = request.getReason() != null && !request.getReason().isBlank()
            ? request.getReason().trim() : "TRANSFERRED";
        String note = request.getNote();

        if (request.getFromGroupId() != null) {
            enrollmentLifecycleService.transfer(studentId, request.getFromGroupId(), toGroup,
                request.getStudyFormat(), reason, note, null);
        } else {
            LocalDate joinDate = billingStatusService.today();
            studentGroupRepository.save(StudentGroup.builder()
                .student(student)
                .group(toGroup)
                .joinDate(joinDate)
                .paymentStartDate(joinDate)
                .studyFormat(request.getStudyFormat())
                .paymentType(PaymentType.MONTHLY)
                .isTrial(false)
                .isActive(true)
                .lessonsAttended(0)
                .build());
            studentGroupRepository.flush();
            billingSnapshotService.refreshStudentFully(studentId);
        }

        String previousStatus = student.getStatus() != null ? student.getStatus().name() : "ACTIVE";
        StudentStatusHistory history = new StudentStatusHistory();
        history.setStudent(student);
        history.setFromStatus(previousStatus);
        history.setToStatus(previousStatus);
        history.setReason(reason);
        history.setNotes(note != null ? note : ("Guruhga o'tkazildi: " + toGroup.getGroupName()));
        history.setChangedAt(LocalDateTime.now());
        studentStatusHistoryRepository.save(history);

        return toDetailResponse(findById(student.getId()));
    }

    @Transactional
    @Audited(action = AuditAction.CREATE, entity = "Student",
        summary = "'Yangi o''quvchi guruhga qo''shildi: ' + #result.firstName + ' ' + #result.lastName",
        entityId = "#result.id",
        label = "#result.firstName + ' ' + #result.lastName")
    public StudentResponse createAndAddStudentToGroup(Long groupId, StudentCreateAndAddRequest req) {
        Group group = groupRepository.findById(groupId)
            .orElseThrow(() -> new ResourceNotFoundException(
                messages.get("error.group.notFound", groupId)));

        long current = studentGroupRepository.countByGroupIdAndIsActiveTrue(groupId);
        if (group.getMaxStudents() != null && current >= group.getMaxStudents()) {
            throw new BadRequestException(messages.get("group.full", group.getMaxStudents()));
        }

        if (studentRepository.findByPhone(req.getPhone()).isPresent()) {
            throw new DuplicateResourceException(
                messages.get("student.phone.duplicate", req.getPhone()));
        }

        Student student = new Student();
        student.setFirstName(req.getFirstName().trim());
        student.setLastName(req.getLastName().trim());
        student.setPhone(req.getPhone().trim());
        student.setGender(req.getGender());
        student.setAdmissionNumber(generateNextAdmissionNumber());
        student.setAdmissionDate(LocalDate.now());
        student.setStatus(StudentStatus.ACTIVE);
        student.setPaymentStartDate(req.getPaymentStartDate());

        PaymentType paymentType = req.getPaymentType() != null
            ? req.getPaymentType() : PaymentType.MONTHLY;
        BigDecimal fee = req.getMonthlyFee();
        if (fee == null && group.getCourse() != null) {
            fee = group.getCourse().getMonthlyPrice();
        }
        if (fee == null) {
            fee = BigDecimal.ZERO;
        }
        if (paymentType == PaymentType.MONTHLY && (req.getMonthlyFee() == null)
                && (group.getCourse() == null || group.getCourse().getMonthlyPrice() == null)) {
            throw new BadRequestException(messages.get("student.monthlyFee.required"));
        }
        student.setMonthlyFee(fee);

        BigDecimal lessonPrice = req.getLessonPrice();
        if (lessonPrice == null && group.getCourse() != null) {
            lessonPrice = group.getCourse().getLessonPrice();
        }

        if (req.getMarketingSource() != null && !req.getMarketingSource().isBlank()) {
            try {
                student.setMarketingSource(MarketingSource.valueOf(req.getMarketingSource().toUpperCase()));
            } catch (IllegalArgumentException e) {
                student.setMarketingSource(MarketingSource.OTHER);
            }
        } else {
            student.setMarketingSource(MarketingSource.OTHER);
        }

        student = studentRepository.save(student);
        applyCreatorAttribution(student, null);
        student = studentRepository.save(student);

        if (req.getParentPhone() != null && !req.getParentPhone().isBlank()) {
            syncParentFromPhone(student, req.getParentPhone(), student.getAddress());
        }

        StudentGroup sg = StudentGroup.builder()
            .student(student)
            .group(group)
            .joinDate(LocalDate.now())
            .paymentStartDate(req.getPaymentStartDate())
            .nextPaymentDate(req.getPaymentStartDate())
            .isTrial(Boolean.TRUE.equals(req.getIsTrial()))
            .isActive(true)
            // Billing v2 (§9.5): override faqat kurs narxidan farq qilsa
            .monthlyPriceOverride(EnrollmentPricing.explicitOverride(fee, group.getCourse()))
            .paymentType(paymentType)
            .lessonPrice(lessonPrice)
            .balance(BigDecimal.ZERO)
            .lessonsAttended(0)
            .build();
        studentGroupRepository.save(sg);
        studentGroupRepository.flush();

        paymentScheduleService.recalculateForStudent(student);

        student = findById(student.getId());
        return toResponse(student);
    }

    @Transactional
    @Audited(action = AuditAction.DELETE, entity = "Student", entityId = "#id")
    public void deleteStudent(Long id) {
        Student student = findById(id);
        student.setStatus(StudentStatus.LEFT);
        studentRepository.save(student);
    }

    @Transactional
    public StudentDetailResponse updatePaymentStartDate(Long studentId, Long groupId,
            LocalDate paymentStartDate, Boolean isTrial) {
        // Billing v2 (§3.6): langar o'zgarishi — ustma-ust davr bo'lsa 409 billing.anchor.overlap
        enrollmentLifecycleService.reanchor(studentId, groupId, paymentStartDate, isTrial);
        return getStudentById(studentId);
    }

    @Transactional(readOnly = true)
    public PageResponse<StudentResponse> searchStudents(String query, int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        Page<Student> studentPage = studentRepository.searchStudents(query, pageable);
        List<Student> students = studentPage.getContent();
        Map<Long, StudentGroup> currentGroups = loadCurrentGroups(
            students.stream().map(Student::getId).collect(Collectors.toList()));
        List<StudentResponse> content = students.stream()
            .map(s -> toResponse(s, currentGroups.get(s.getId())))
            .collect(Collectors.toList());
        return PageResponse.<StudentResponse>builder()
            .content(content).pageNumber(page).pageSize(size)
            .totalElements(studentPage.getTotalElements())
            .totalPages(studentPage.getTotalPages()).last(studentPage.isLast())
            .build();
    }

    @Transactional
    public StudentResponse updatePhoto(Long id, String photoUrl) {
        Student student = findById(id);
        if (photoUrl != null && !photoUrl.isBlank()) {
            student.setPhotoUrl(photoUrl.trim());
        }
        return toResponse(studentRepository.save(student));
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getStudentStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("total", studentRepository.count());
        stats.put("active", studentRepository.countByStatus(StudentStatus.ACTIVE));
        stats.put("frozen", studentRepository.countByStatus(StudentStatus.FROZEN));
        stats.put("finished", studentRepository.countByStatus(StudentStatus.FINISHED));
        stats.put("left", studentRepository.countByStatus(StudentStatus.LEFT));

        Map<String, Long> byGender = new LinkedHashMap<>();
        studentRepository.countByGender().forEach(row -> byGender.put((String) row[0], (Long) row[1]));
        stats.put("byGender", byGender);

        Map<String, Long> bySource = new LinkedHashMap<>();
        studentRepository.countByMarketingSourceGrouped()
            .forEach(row -> bySource.put(row[0].toString(), (Long) row[1]));
        stats.put("bySource", bySource);

        return stats;
    }

    @Transactional(readOnly = true)
    public byte[] exportStudentsCsv() {
        List<Student> students = studentRepository.findAll(Sort.by("createdAt").descending());
        StringBuilder csv = new StringBuilder();
        csv.append("ID,UUID,First Name,Last Name,Phone,Admission Number,Gender,Status,Marketing Source,Created At\n");
        for (Student s : students) {
            csv.append(s.getId()).append(",")
               .append(s.getUuid()).append(",")
               .append(esc(s.getFirstName())).append(",")
               .append(esc(s.getLastName())).append(",")
               .append(esc(s.getPhone())).append(",")
               .append(esc(s.getAdmissionNumber())).append(",")
               .append(esc(s.getGender())).append(",")
               .append(s.getStatus()).append(",")
               .append(s.getMarketingSource()).append(",")
               .append(s.getCreatedAt()).append("\n");
        }
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    public Student findById(Long id) {
        return studentRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException(
                messages.get("error.student.notFound", id)));
    }

    private Student buildFromRequest(Student s, StudentRequest req, boolean isCreate) {
        s.setFirstName(req.getFirstName());
        s.setLastName(req.getLastName());
        s.setPhone(req.getPhone());
        s.setParentPhone(req.getParentPhone());
        s.setBirthDate(req.getBirthDate());
        s.setGender(req.getGender());
        
        if (req.getMarketingSource() != null && !req.getMarketingSource().isBlank()) {
            try {
                s.setMarketingSource(MarketingSource.valueOf(req.getMarketingSource().toUpperCase()));
            } catch (IllegalArgumentException e) {
                s.setMarketingSource(MarketingSource.OTHER);
            }
        }
        
        if (req.getStatus() != null && !req.getStatus().isBlank()) {
            try {
                s.setStatus(StudentStatus.valueOf(req.getStatus().toUpperCase()));
            } catch (IllegalArgumentException e) {
                s.setStatus(StudentStatus.ACTIVE);
            }
        }
        
        s.setNotes(req.getNotes());
        s.setAddress(req.getAddress());
        if (isCreate || (req.getPhotoUrl() != null && !req.getPhotoUrl().isBlank())) {
            s.setPhotoUrl(req.getPhotoUrl());
        }
        if (isCreate || (req.getAdmissionNumber() != null && !req.getAdmissionNumber().isBlank())) {
            s.setAdmissionNumber(req.getAdmissionNumber());
        }
        if (req.getAdmissionDate() != null) {
            s.setAdmissionDate(req.getAdmissionDate());
        }
        return s;
    }

    private StudentResponse toResponse(Student s) {
        StudentGroup current = studentGroupRepository.findActiveByStudentId(s.getId())
            .stream()
            .max(Comparator.comparing(StudentGroup::getJoinDate,
                Comparator.nullsLast(Comparator.naturalOrder())))
            .orElse(null);
        return toResponse(s, current);
    }

    private StudentResponse toResponse(Student s, StudentGroup currentGroup) {
        StudentResponse.StudentResponseBuilder builder = StudentResponse.builder()
            .id(s.getId()).uuid(s.getUuid())
            .firstName(s.getFirstName()).lastName(s.getLastName())
            .phone(s.getPhone()).parentPhone(s.getParentPhone())
            .birthDate(s.getBirthDate()).gender(s.getGender())
            .marketingSource(s.getMarketingSource())
            .status(s.getStatus()).notes(s.getNotes())
            .address(s.getAddress()).photoUrl(s.getPhotoUrl())
            .admissionNumber(s.getAdmissionNumber()).admissionDate(s.getAdmissionDate())
            .referralStudentId(s.getReferralStudent() != null ? s.getReferralStudent().getId() : null)
            .paymentStatus(s.getPaymentStatus() != null ? s.getPaymentStatus() : PaymentStatus.PENDING)
            .paymentStartDate(s.getPaymentStartDate())
            .nextPaymentDate(s.getNextPaymentDate())
            .monthlyFee(s.getMonthlyFee())
            .balance(s.getBalance() != null ? s.getBalance() : BigDecimal.ZERO)
            .debt(s.getDebt() != null ? s.getDebt() : BigDecimal.ZERO)
            .nextPaymentAmount(s.getNextPaymentAmount())
            .createdAt(s.getCreatedAt());

        if (currentGroup != null && currentGroup.getGroup() != null) {
            builder.currentGroupId(currentGroup.getGroup().getId())
                .currentGroupName(currentGroup.getGroup().getGroupName())
                .studyFormat(currentGroup.getStudyFormat());
        }
        return builder.build();
    }

    private Map<Long, StudentGroup> loadCurrentGroups(Collection<Long> studentIds) {
        if (studentIds == null || studentIds.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<Long, StudentGroup> map = new LinkedHashMap<>();
        for (StudentGroup sg : studentGroupRepository.findActiveByStudentIds(studentIds)) {
            Long sid = sg.getStudent() != null ? sg.getStudent().getId() : null;
            if (sid == null) {
                continue;
            }
            // Query ordered by joinDate DESC — keep first (latest) per student
            map.putIfAbsent(sid, sg);
        }
        return map;
    }

    /**
     * {@code studyFormat} null bo'lsa yozuvda ham null qoladi — format
     * majburiy emas va ortga qarab to'ldirilmaydi.
     */
    private void addStudentToGroupIfNeeded(Student student, Long groupId, StudyFormat studyFormat) {
        Group group = groupRepository.findById(groupId).orElse(null);
        if (group == null) {
            return;
        }
        if (studentGroupRepository.findByStudentIdAndGroupIdAndIsActiveTrue(student.getId(), groupId)
            .isPresent()) {
            return;
        }
        long current = studentGroupRepository.countByGroupIdAndIsActiveTrue(groupId);
        if (group.getMaxStudents() != null && current >= group.getMaxStudents()) {
            throw new BadRequestException(messages.get("group.full", group.getMaxStudents()));
        }
        LocalDate joinDate = LocalDate.now();
        BigDecimal fee = group.getCourse() != null && group.getCourse().getMonthlyPrice() != null
            ? group.getCourse().getMonthlyPrice() : BigDecimal.ZERO;
        student.setPaymentStartDate(joinDate);
        student.setMonthlyFee(fee);
        studentRepository.save(student);

        StudentGroup sg = StudentGroup.builder()
            .student(student)
            .group(group)
            .joinDate(joinDate)
            .paymentStartDate(joinDate)
            .nextPaymentDate(joinDate)
            .studyFormat(studyFormat)
            .isTrial(false)
            .isActive(true)
            .lessonsAttended(0)
            .build();
        studentGroupRepository.save(sg);
        studentGroupRepository.flush();
        paymentScheduleService.recalculateForStudent(student);
    }

    private StudentDetailResponse toDetailResponse(Student s) {
        List<StudentDetailResponse.GroupSummary> activeGroups = studentGroupRepository
            .findActiveByStudentId(s.getId()).stream()
            .map(this::toGroupSummary)
            .collect(Collectors.toList());

        StudentGroup current = studentGroupRepository.findActiveByStudentId(s.getId())
            .stream()
            .max(Comparator.comparing(StudentGroup::getJoinDate,
                Comparator.nullsLast(Comparator.naturalOrder())))
            .orElse(null);

        List<StudentDetailResponse.StudentParentInfo> parents = studentParentRepository
            .findByStudentId(s.getId()).stream()
            .map(sp -> StudentDetailResponse.StudentParentInfo.builder()
                .parentId(sp.getParent().getId())
                .fullName(sp.getParent().getFullName())
                .phone(sp.getParent().getPhone())
                .address(sp.getParent().getAddress())
                .relation(sp.getRelation())
                .isPrimary(sp.getIsPrimary())
                .build())
            .collect(Collectors.toList());

        List<StudentDetailResponse.PaymentSummary> paymentHistory = paymentRepository
            .findByStudent_IdOrderByCreatedAtDesc(s.getId()).stream()
            .limit(20)
            .map(this::toPaymentSummary)
            .collect(Collectors.toList());

        Map<String, Integer> attendanceSummary = new LinkedHashMap<>();
        attendanceSummary.put("present", 0);
        attendanceSummary.put("absent", 0);
        attendanceSummary.put("late", 0);
        for (Object[] row : attendanceRepository.countByStudentGrouped(s.getId())) {
            AttendanceStatus st = (AttendanceStatus) row[0];
            int c = ((Number) row[1]).intValue();
            if (st != null) {
                attendanceSummary.put(st.name().toLowerCase(Locale.ROOT), c);
            }
        }

        StudentDetailResponse.StudentDetailResponseBuilder builder = StudentDetailResponse.builder()
            .id(s.getId()).uuid(s.getUuid())
            .firstName(s.getFirstName()).lastName(s.getLastName())
            .phone(s.getPhone()).parentPhone(s.getParentPhone())
            .birthDate(s.getBirthDate()).gender(s.getGender())
            .marketingSource(s.getMarketingSource())
            .status(s.getStatus()).notes(s.getNotes())
            .address(s.getAddress()).photoUrl(s.getPhotoUrl())
            .admissionNumber(s.getAdmissionNumber()).admissionDate(s.getAdmissionDate())
            .referralStudentId(s.getReferralStudent() != null ? s.getReferralStudent().getId() : null)
            .paymentStatus(s.getPaymentStatus() != null ? s.getPaymentStatus() : PaymentStatus.PENDING)
            .paymentStartDate(s.getPaymentStartDate())
            .nextPaymentDate(s.getNextPaymentDate())
            .monthlyFee(s.getMonthlyFee())
            .balance(s.getBalance() != null ? s.getBalance() : BigDecimal.ZERO)
            .debt(s.getDebt() != null ? s.getDebt() : BigDecimal.ZERO)
            .nextPaymentAmount(s.getNextPaymentAmount())
            .activeGroups(activeGroups)
            .paymentHistory(paymentHistory)
            .attendanceSummary(attendanceSummary)
            .parents(parents)
            .createdAt(s.getCreatedAt());

        if (current != null && current.getGroup() != null) {
            builder.currentGroupId(current.getGroup().getId())
                .currentGroupName(current.getGroup().getGroupName())
                .studyFormat(current.getStudyFormat());
        }
        return builder.build();
    }

    private StudentDetailResponse.GroupSummary toGroupSummary(StudentGroup sg) {
        Group g = sg.getGroup();
        if (g == null) {
            return StudentDetailResponse.GroupSummary.builder()
                .studyFormat(sg.getStudyFormat())
                .joinDate(sg.getJoinDate())
                .leaveDate(sg.getLeaveDate())
                .isActive(sg.getIsActive())
                .paymentStatus(sg.getPaymentStatus() != null ? sg.getPaymentStatus().name() : null)
                .monthlyPrice(sg.getMonthlyPriceOverride())
                .build();
        }
        Course course = g.getCourse();
        BigDecimal monthlyPrice = sg.getMonthlyPriceOverride() != null
            ? sg.getMonthlyPriceOverride()
            : (course != null ? course.getMonthlyPrice() : null);
        return StudentDetailResponse.GroupSummary.builder()
            .studyFormat(sg.getStudyFormat())
            .groupId(g.getId())
            .groupName(g.getGroupName())
            .courseName(course != null ? course.getCourseName() : null)
            .teacherName(g.getTeacher() != null
                ? g.getTeacher().getFirstName() + " " + g.getTeacher().getLastName() : null)
            .paymentStatus(sg.getPaymentStatus() != null ? sg.getPaymentStatus().name() : null)
            .joinDate(sg.getJoinDate())
            .leaveDate(sg.getLeaveDate())
            .isActive(sg.getIsActive())
            .monthlyPrice(monthlyPrice)
            // Billing v2 — snapshot (§8): har SG alohida qator
            .studentGroupId(sg.getId())
            .paymentType(sg.getPaymentType() != null ? sg.getPaymentType().name() : null)
            .balance(sg.getBalance() != null ? sg.getBalance() : BigDecimal.ZERO)
            .debt(sg.getBalance() != null && sg.getBalance().signum() < 0
                ? sg.getBalance().negate() : BigDecimal.ZERO)
            .debtSince(sg.getDebtSince())
            .nextPaymentDate(sg.getNextPaymentDate())
            .nextPaymentAmount(sg.getNextPaymentAmount())
            .effectiveFee(sg.getPaymentType() == PaymentType.PER_LESSON
                ? com.crm.billing.EnrollmentPricing.effectiveLessonPrice(sg)
                : com.crm.billing.EnrollmentPricing.effectiveMonthlyFee(sg))
            .discountPercentage(sg.getDiscountPercentage())
            .billingDay(sg.getPaymentStartDate() != null ? sg.getPaymentStartDate().getDayOfMonth() : null)
            .paymentStartDate(sg.getPaymentStartDate())
            .frozenFrom(sg.getFrozenFrom())
            .isTrial(sg.getIsTrial())
            .build();
    }

    private StudentDetailResponse.PaymentSummary toPaymentSummary(Payment p) {
        return StudentDetailResponse.PaymentSummary.builder()
            .id(p.getId())
            .receiptNumber(p.getReceiptNumber())
            .amount(p.getAmount())
            .formattedAmount(formatUzs(p.getAmount()))
            .paymentDate(p.getPaymentDate())
            .paymentMethod(p.getPaymentMethod())
            .groupName(p.getGroup() != null ? p.getGroup().getGroupName() : null)
            .status(p.getStatus())
            .build();
    }

    private static String formatUzs(BigDecimal amount) {
        if (amount == null) {
            return "0 so'm";
        }
        long v = amount.setScale(0, RoundingMode.HALF_UP).longValue();
        return String.format(Locale.US, "%,d", v).replace(',', ' ') + " so'm";
    }

    private String esc(String val) {
        if (val == null) return "";
        return "\"" + val.replace("\"", "\"\"") + "\"";
    }

    public List<Map<String, Object>> getTrialStudents() {
        return studentGroupRepository
            .findActiveTrials()
            .stream()
            .map(sg -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("studentId", sg.getStudent().getId());
                m.put("studentName",
                    sg.getStudent().getFirstName() + " " +
                    sg.getStudent().getLastName());
                m.put("phone", sg.getStudent().getPhone());
                m.put("groupId", sg.getGroup().getId());
                m.put("groupName", sg.getGroup().getGroupName());
                m.put("joinDate", sg.getJoinDate());
                m.put("lessonsAttended", sg.getLessonsAttended());
                return m;
            })
            .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> getStudentHistory(Long studentId) {
        findById(studentId);
        return studentStatusHistoryRepository
            .findByStudent_IdOrderByChangedAtDesc(studentId)
            .stream()
            .map(h -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", h.getId());
                m.put("fromStatus", h.getFromStatus());
                m.put("toStatus", h.getToStatus());
                m.put("reason", h.getReason());
                m.put("notes", h.getNotes());
                m.put("changedAt", h.getChangedAt());
                return m;
            })
            .collect(Collectors.toList());
    }

    /** Billing v2 (§6.7): preview va freeze bitta {@code FreezePlan} dan. */
    @Transactional(readOnly = true)
    public FreezeStudentResponse previewFreeze(Long studentId, FreezeStudentRequest request) {
        FreezeStudentRequest r = request != null ? request : new FreezeStudentRequest();
        return enrollmentLifecycleService.previewFreeze(studentId, r.getGroupId(), r.getFreezeDate());
    }

    /**
     * Billing v2 (§6.7): faqat tanlangan yozilma muzlatiladi, MONTHLY da proporsional
     * {@code PERIOD_REFUND}. O'quvchi FROZEN — faqat barcha faol guruhlari muzlatilganda.
     */
    @Transactional
    @Audited(action = AuditAction.UPDATE, entity = "Student",
        summary = "'O''quvchi muzlatildi'",
        entityId = "#studentId")
    public FreezeStudentResponse freezeStudent(Long studentId, FreezeStudentRequest request) {
        FreezeStudentRequest r = request != null ? request : new FreezeStudentRequest();
        return enrollmentLifecycleService.freeze(studentId, r.getGroupId(), r.getFreezeDate(),
            r.getReason(), r.getNote());
    }

    /**
     * Billing v2 (§6.7): o'sha SG qayta faollashadi — yangi SG yaratilmaydi, balans
     * ko'chirilmaydi; yangi langardan accrual.
     */
    @Transactional
    @Audited(action = AuditAction.UPDATE, entity = "Student",
        summary = "'O''quvchi muzlatishdan chiqarildi'",
        entityId = "#studentId")
    public StudentDetailResponse unfreezeStudent(Long studentId, UnfreezeStudentRequest request) {
        findById(studentId);
        enrollmentLifecycleService.unfreeze(studentId, request.getGroupId(), request.getPaymentStartDate());
        return getStudentById(studentId);
    }

    @Transactional(readOnly = true)
    public List<BalanceHistoryItemDto> getBalanceHistory(
            Long studentId, Long groupId, LocalDate from, LocalDate to) {
        return balanceTransactionService.getHistory(studentId, groupId, from, to);
    }

    @Transactional
    public BalanceHistoryItemDto adjustBalance(Long studentId, BalanceAdjustRequest request) {
        return balanceTransactionService.manualAdjust(
            studentId, request.getGroupId(), request.getAmount(), request.getNote(), request.getEffectiveDate());
    }

    /** Billing v2 (§13 #6): SA qo'lda guruhlar orasida balans ko'chiradi. */
    @Transactional
    public List<BalanceHistoryItemDto> transferBalance(Long studentId, BalanceTransferRequest request) {
        return balanceTransactionService.transferBetweenGroups(studentId, request.getFromGroupId(),
            request.getToGroupId(), request.getAmount(), request.getNote());
    }

    /**
     * createdBy = joriy user; attributedUserId = attributedOverride ?? createdBy.
     * Eski yozuvlar NULL qoladi (repair qilinmaydi).
     */
    public void applyCreatorAttribution(Student student, User attributedOverride) {
        if (student == null) {
            return;
        }
        User creator = null;
        try {
            creator = teacherAccessService.getCurrentUserOrThrow();
        } catch (Exception ignored) {
            // background / no auth
        }
        if (creator != null) {
            student.setCreatedBy(creator);
        }
        if (attributedOverride != null) {
            student.setAttributedUserId(attributedOverride.getId());
        } else if (creator != null) {
            student.setAttributedUserId(creator.getId());
        }
    }
}
