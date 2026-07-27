package com.crm.service;

import com.crm.dto.request.BalanceAdjustRequest;
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
import com.crm.entity.enums.AttendanceStatus;
import com.crm.entity.enums.MarketingSource;
import com.crm.entity.enums.PaymentStatus;
import com.crm.entity.enums.PaymentType;
import com.crm.entity.enums.StudentStatus;
import com.crm.exception.BadRequestException;
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
        return studentRepository.findArchivedOrFrozenWithBalanceSignals(LocalDate.now()).stream()
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
    public StudentResponse createStudent(StudentRequest request) {
        if (studentRepository.findByPhone(request.getPhone()).isPresent()) {
            throw new DuplicateResourceException("Student with phone already exists: " + request.getPhone());
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

        if (student.getPaymentStatus() == null) {
            student.setPaymentStatus(PaymentStatus.PENDING);
        }
        if (student.getBalance() == null) {
            student.setBalance(BigDecimal.ZERO);
        }

        if (request.getReferralStudentId() != null) {
            Student referral = findById(request.getReferralStudentId());
            student.setReferralStudent(referral);
        }

        Student saved = studentRepository.save(student);
        syncParents(saved, request.getParents(), request.getParentPhone());
        if (request.getGroupId() != null) {
            addStudentToGroupIfNeeded(saved, request.getGroupId());
        }

        return toResponse(findById(saved.getId()));
    }

    @Transactional
    public StudentResponse updateStudent(Long id, StudentRequest request) {
        Student student = findById(id);

        studentRepository.findByPhone(request.getPhone())
            .filter(s -> !s.getId().equals(id))
            .ifPresent(s -> { throw new DuplicateResourceException("Phone already used by another student"); });

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
                for (StudentGroup sg : studentGroupRepository.findActiveByStudentId(student.getId())) {
                    if (!sg.getGroup().getId().equals(target.getId())) {
                        sg.setIsActive(false);
                        sg.setLeaveDate(LocalDate.now());
                        studentGroupRepository.save(sg);
                    }
                }
                addStudentToGroupIfNeeded(student, target.getId());
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

    @Transactional
    public StudentDetailResponse transferGroup(Long studentId, TransferGroupRequest request) {
        if (request.getToGroupId() == null) {
            throw new BadRequestException("toGroupId majburiy");
        }

        Student student = findById(studentId);
        Group toGroup = groupRepository.findById(request.getToGroupId())
            .orElseThrow(() -> new ResourceNotFoundException("Group", request.getToGroupId()));

        if (studentGroupRepository.existsByStudentIdAndGroupIdAndIsActiveTrue(
                studentId, request.getToGroupId())) {
            throw new BadRequestException("O'quvchi bu guruhda bor");
        }

        String reason = request.getReason() != null && !request.getReason().isBlank()
            ? request.getReason().trim() : "TRANSFERRED";
        String note = request.getNote();

        if (request.getFromGroupId() != null) {
            StudentGroup from = studentGroupRepository
                .findByStudentIdAndGroupIdAndIsActiveTrue(studentId, request.getFromGroupId())
                .orElseThrow(() -> new ResourceNotFoundException(
                    "Student is not active in group " + request.getFromGroupId()));
            LocalDate today = LocalDate.now();
            from.setIsActive(false);
            from.setLeaveDate(today);
            from.setExitDate(today);
            from.setExitReason(reason);
            from.setExitNotes(note);
            studentGroupRepository.save(from);
        }

        long current = studentGroupRepository.countByGroupIdAndIsActiveTrue(toGroup.getId());
        if (toGroup.getMaxStudents() != null && current >= toGroup.getMaxStudents()) {
            throw new BadRequestException("Guruh to'lgan! Max: " + toGroup.getMaxStudents());
        }

        LocalDate joinDate = LocalDate.now();
        BigDecimal fee = toGroup.getCourse() != null && toGroup.getCourse().getMonthlyPrice() != null
            ? toGroup.getCourse().getMonthlyPrice() : BigDecimal.ZERO;
        student.setPaymentStartDate(joinDate);
        student.setMonthlyFee(fee);
        student.setPaymentStatus(PaymentStatus.PENDING);
        studentRepository.save(student);

        StudentGroup newEnrollment = StudentGroup.builder()
            .student(student)
            .group(toGroup)
            .joinDate(joinDate)
            .paymentStartDate(joinDate)
            .nextPaymentDate(joinDate)
            .isTrial(false)
            .isActive(true)
            .monthlyPriceOverride(fee)
            .paymentStatus("PENDING")
            .lessonsAttended(0)
            .build();
        studentGroupRepository.save(newEnrollment);
        studentGroupRepository.flush();
        paymentScheduleService.recalculateForStudent(student);

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
    public StudentResponse createAndAddStudentToGroup(Long groupId, StudentCreateAndAddRequest req) {
        Group group = groupRepository.findById(groupId)
            .orElseThrow(() -> new ResourceNotFoundException("Group", groupId));

        long current = studentGroupRepository.countByGroupIdAndIsActiveTrue(groupId);
        if (group.getMaxStudents() != null && current >= group.getMaxStudents()) {
            throw new BadRequestException("Guruh to'lgan! Max: " + group.getMaxStudents());
        }

        if (studentRepository.findByPhone(req.getPhone()).isPresent()) {
            throw new DuplicateResourceException("Student with phone already exists: " + req.getPhone());
        }

        Student student = new Student();
        student.setFirstName(req.getFirstName().trim());
        student.setLastName(req.getLastName().trim());
        student.setPhone(req.getPhone().trim());
        student.setGender(req.getGender());
        student.setAdmissionNumber(generateNextAdmissionNumber());
        student.setAdmissionDate(LocalDate.now());
        student.setStatus(StudentStatus.ACTIVE);
        student.setPaymentStatus(PaymentStatus.PENDING);
        student.setPaymentStartDate(req.getPaymentStartDate());
        student.setBalance(BigDecimal.ZERO);

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
            throw new BadRequestException("Oylik to'lov summasi majburiy");
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
            .monthlyPriceOverride(fee)
            .paymentType(paymentType)
            .lessonPrice(lessonPrice)
            .lessonsPurchased(0)
            .lessonsUsed(0)
            .balance(BigDecimal.ZERO)
            .paymentStatus(Boolean.TRUE.equals(req.getIsTrial()) ? "TRIAL" : "PENDING")
            .lessonsAttended(0)
            .build();
        studentGroupRepository.save(sg);
        studentGroupRepository.flush();

        paymentScheduleService.recalculateForStudent(student);

        student = findById(student.getId());
        return toResponse(student);
    }

    @Transactional
    public void deleteStudent(Long id) {
        Student student = findById(id);
        student.setStatus(StudentStatus.LEFT);
        studentRepository.save(student);
    }

    @Transactional
    public StudentDetailResponse updatePaymentStartDate(Long studentId,
            LocalDate paymentStartDate, Boolean isTrial) {
        paymentScheduleService.updatePaymentStartDate(studentId, paymentStartDate, isTrial);
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
            .orElseThrow(() -> new ResourceNotFoundException("Student", id));
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
            .createdAt(s.getCreatedAt());

        if (currentGroup != null && currentGroup.getGroup() != null) {
            builder.currentGroupId(currentGroup.getGroup().getId())
                .currentGroupName(currentGroup.getGroup().getGroupName());
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

    private void addStudentToGroupIfNeeded(Student student, Long groupId) {
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
            throw new BadRequestException("Guruh to'lgan! Max: " + group.getMaxStudents());
        }
        LocalDate joinDate = LocalDate.now();
        BigDecimal fee = group.getCourse() != null && group.getCourse().getMonthlyPrice() != null
            ? group.getCourse().getMonthlyPrice() : BigDecimal.ZERO;
        student.setPaymentStartDate(joinDate);
        student.setMonthlyFee(fee);
        student.setPaymentStatus(PaymentStatus.PENDING);
        studentRepository.save(student);

        StudentGroup sg = StudentGroup.builder()
            .student(student)
            .group(group)
            .joinDate(joinDate)
            .paymentStartDate(joinDate)
            .nextPaymentDate(joinDate)
            .isTrial(false)
            .isActive(true)
            .monthlyPriceOverride(fee)
            .paymentStatus("PENDING")
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
            .activeGroups(activeGroups)
            .paymentHistory(paymentHistory)
            .attendanceSummary(attendanceSummary)
            .parents(parents)
            .createdAt(s.getCreatedAt());

        if (current != null && current.getGroup() != null) {
            builder.currentGroupId(current.getGroup().getId())
                .currentGroupName(current.getGroup().getGroupName());
        }
        return builder.build();
    }

    private StudentDetailResponse.GroupSummary toGroupSummary(StudentGroup sg) {
        Group g = sg.getGroup();
        if (g == null) {
            return StudentDetailResponse.GroupSummary.builder()
                .joinDate(sg.getJoinDate())
                .leaveDate(sg.getLeaveDate())
                .isActive(sg.getIsActive())
                .paymentStatus(sg.getPaymentStatus())
                .monthlyPrice(sg.getMonthlyPriceOverride())
                .build();
        }
        Course course = g.getCourse();
        BigDecimal monthlyPrice = sg.getMonthlyPriceOverride() != null
            ? sg.getMonthlyPriceOverride()
            : (course != null ? course.getMonthlyPrice() : null);
        return StudentDetailResponse.GroupSummary.builder()
            .groupId(g.getId())
            .groupName(g.getGroupName())
            .courseName(course != null ? course.getCourseName() : null)
            .teacherName(g.getTeacher() != null
                ? g.getTeacher().getFirstName() + " " + g.getTeacher().getLastName() : null)
            .paymentStatus(sg.getPaymentStatus())
            .joinDate(sg.getJoinDate())
            .leaveDate(sg.getLeaveDate())
            .isActive(sg.getIsActive())
            .monthlyPrice(monthlyPrice)
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

    public List<StudentResponse> getStudentsByStatus(List<String> statuses) {
        List<com.crm.entity.enums.StudentStatus> enumStatuses = statuses.stream()
            .map(s -> {
                try {
                    return com.crm.entity.enums.StudentStatus.valueOf(s);
                } catch (IllegalArgumentException e) {
                    return null;
                }
            })
            .filter(java.util.Objects::nonNull)
            .collect(Collectors.toList());

        return studentRepository.findByStatusIn(enumStatuses)
            .stream()
            .map(this::toResponse)
            .collect(Collectors.toList());
    }

    public List<Map<String, Object>> getTrialStudents() {
        return studentGroupRepository
            .findByPaymentStatusAndIsActiveTrue("TRIAL")
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

    @Transactional
    public FreezeStudentResponse freezeStudent(Long studentId, FreezeStudentRequest request) {
        Student student = findById(studentId);
        if (student.getStatus() == StudentStatus.FROZEN) {
            throw new BadRequestException("O'quvchi allaqachon muzlatilgan");
        }

        List<StudentGroup> enrollments;
        if (request.getGroupId() != null) {
            StudentGroup sg = studentGroupRepository
                .findByStudentIdAndGroupIdAndIsActiveTrue(studentId, request.getGroupId())
                .orElseThrow(() -> new ResourceNotFoundException("StudentGroup", request.getGroupId()));
            enrollments = List.of(sg);
        } else {
            enrollments = studentGroupRepository.findActiveByStudentId(studentId);
        }
        if (enrollments.isEmpty()) {
            throw new BadRequestException("Muzlatish uchun active guruh yo'q");
        }

        List<AttendanceStatus> billable = List.of(
            AttendanceStatus.PRESENT, AttendanceStatus.ABSENT, AttendanceStatus.LATE);

        BigDecimal totalAdded = BigDecimal.ZERO;
        List<FreezeStudentResponse.FrozenGroupBreakdown> breakdowns = new ArrayList<>();
        String previousStatus = student.getStatus() != null ? student.getStatus().name() : "ACTIVE";

        for (StudentGroup sg : enrollments) {
            BigDecimal lessonPrice = PaymentScheduleService.resolveLessonPrice(sg);
            LocalDate from = sg.getJoinDate() != null ? sg.getJoinDate() : LocalDate.EPOCH;
            int lessonsUsed = (int) attendanceRepository.countByStudentAndGroupAndStatusesSince(
                studentId, sg.getGroup().getId(), billable, from);

            BigDecimal used = lessonPrice.multiply(BigDecimal.valueOf(lessonsUsed));
            BigDecimal paid = paymentRepository.sumCreditsByStudentGroupId(sg.getId());
            if (paid == null || paid.compareTo(BigDecimal.ZERO) == 0) {
                paid = paymentRepository.sumCreditsByStudentAndGroup(studentId, sg.getGroup().getId());
            }
            if (paid == null) {
                paid = BigDecimal.ZERO;
            }

            BigDecimal groupBalance = paid.subtract(used);
            if (groupBalance.compareTo(BigDecimal.ZERO) < 0) {
                groupBalance = BigDecimal.ZERO;
            }
            totalAdded = totalAdded.add(groupBalance);

            sg.setIsActive(false);
            sg.setLeaveDate(LocalDate.now());
            sg.setExitDate(LocalDate.now());
            sg.setExitReason("FROZEN");
            sg.setExitNotes(request.getNote());
            sg.setPaymentStatus("FROZEN");
            sg.setLessonsUsed(lessonsUsed);

            // Balansni paid-used ga moslashtirish + FREEZE audit
            BigDecimal currentSgBalance = sg.getBalance() != null ? sg.getBalance() : BigDecimal.ZERO;
            BigDecimal delta = groupBalance.subtract(currentSgBalance);
            if (delta.compareTo(BigDecimal.ZERO) != 0) {
                balanceTransactionService.record(
                    sg,
                    com.crm.entity.enums.BalanceTransactionType.FREEZE,
                    delta,
                    null,
                    "Muzlatish hisobi (paid - used): " + groupBalance.toPlainString()
                        + (request.getNote() != null ? " | " + request.getNote() : ""));
            } else {
                balanceTransactionService.record(
                    sg,
                    com.crm.entity.enums.BalanceTransactionType.FREEZE,
                    BigDecimal.ZERO,
                    null,
                    "Muzlatish: balans=" + groupBalance.toPlainString()
                        + (request.getNote() != null ? " | " + request.getNote() : ""));
            }

            studentGroupRepository.save(sg);

            breakdowns.add(FreezeStudentResponse.FrozenGroupBreakdown.builder()
                .groupId(sg.getGroup().getId())
                .groupName(sg.getGroup().getGroupName())
                .lessonsUsed(lessonsUsed)
                .used(used)
                .paid(paid)
                .balance(groupBalance)
                .build());
        }

        balanceTransactionService.syncStudentBalanceFromGroups(student);
        student.setStatus(StudentStatus.FROZEN);
        student.setNextPaymentDate(null);
        student.setPaymentStatus(PaymentStatus.FROZEN);
        studentRepository.save(student);

        StudentStatusHistory history = new StudentStatusHistory();
        history.setStudent(student);
        history.setFromStatus(previousStatus);
        history.setToStatus(StudentStatus.FROZEN.name());
        history.setReason(request.getReason() != null ? request.getReason() : "FROZEN");
        history.setNotes("Muzlatish balansi: " + totalAdded.toPlainString()
            + (request.getNote() != null ? " | " + request.getNote() : ""));
        history.setChangedAt(LocalDateTime.now());
        studentStatusHistoryRepository.save(history);

        paymentScheduleService.clearPaymentSchedule(studentId);

        return FreezeStudentResponse.builder()
            .studentId(studentId)
            .totalBalance(student.getBalance())
            .groups(breakdowns)
            .build();
    }

    @Transactional
    public StudentDetailResponse unfreezeStudent(Long studentId, UnfreezeStudentRequest request) {
        Student student = findById(studentId);
        if (student.getStatus() != StudentStatus.FROZEN) {
            throw new BadRequestException("O'quvchi muzlatilgan emas");
        }

        Group group = groupRepository.findById(request.getGroupId())
            .orElseThrow(() -> new ResourceNotFoundException("Group", request.getGroupId()));

        if (studentGroupRepository.findByStudentIdAndGroupIdAndIsActiveTrue(studentId, group.getId())
                .isPresent()) {
            throw new BadRequestException("O'quvchi bu guruhda allaqachon active");
        }

        String previousStatus = student.getStatus().name();
        student.setStatus(StudentStatus.ACTIVE);
        student.setPaymentStatus(PaymentStatus.PENDING);
        student.setPaymentStartDate(request.getPaymentStartDate());
        // balance saqlanadi
        studentRepository.save(student);

        BigDecimal fee = group.getCourse() != null && group.getCourse().getMonthlyPrice() != null
            ? group.getCourse().getMonthlyPrice() : BigDecimal.ZERO;
        BigDecimal lessonPrice = group.getCourse() != null ? group.getCourse().getLessonPrice() : null;

        StudentGroup sg = StudentGroup.builder()
            .student(student)
            .group(group)
            .joinDate(LocalDate.now())
            .paymentStartDate(request.getPaymentStartDate())
            .nextPaymentDate(request.getPaymentStartDate())
            .isTrial(false)
            .isActive(true)
            .monthlyPriceOverride(fee)
            .paymentType(PaymentType.MONTHLY)
            .lessonPrice(lessonPrice)
            .lessonsPurchased(0)
            .lessonsUsed(0)
            .balance(BigDecimal.ZERO)
            .paymentStatus("PENDING")
            .lessonsAttended(0)
            .build();
        studentGroupRepository.save(sg);
        studentGroupRepository.flush();

        // Oldingi muzlatilgan guruh balansini yangi SG ga o'tkazish
        StudentGroup frozenSg = studentGroupRepository.findByStudentIdOrderByJoinDateDesc(studentId)
            .stream()
            .filter(g -> g.getGroup() != null && group.getId().equals(g.getGroup().getId())
                && "FROZEN".equals(g.getExitReason()))
            .findFirst()
            .orElse(null);
        BigDecimal carry = frozenSg != null && frozenSg.getBalance() != null
            ? frozenSg.getBalance() : BigDecimal.ZERO;
        if (carry.compareTo(BigDecimal.ZERO) > 0 && frozenSg != null) {
            balanceTransactionService.record(
                frozenSg,
                com.crm.entity.enums.BalanceTransactionType.UNFREEZE,
                carry.negate(),
                null,
                "Balans yangi guruhga o'tkazildi");
            balanceTransactionService.record(
                sg,
                com.crm.entity.enums.BalanceTransactionType.UNFREEZE,
                carry,
                null,
                "Muzlatishdan qaytganda tiklandi");
        } else {
            balanceTransactionService.record(
                sg,
                com.crm.entity.enums.BalanceTransactionType.UNFREEZE,
                BigDecimal.ZERO,
                null,
                "Muzlatishdan chiqarildi: " + group.getGroupName());
        }

        StudentStatusHistory history = new StudentStatusHistory();
        history.setStudent(student);
        history.setFromStatus(previousStatus);
        history.setToStatus(StudentStatus.ACTIVE.name());
        history.setReason("UNFROZEN");
        history.setNotes("Guruhga qayta qo'shildi: " + group.getGroupName());
        history.setChangedAt(LocalDateTime.now());
        studentStatusHistoryRepository.save(history);

        paymentScheduleService.recalculateForStudent(student);
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
            studentId, request.getGroupId(), request.getAmount(), request.getNote());
    }

    @Transactional(readOnly = true)
    public List<FrozenStudentResponse> getFrozenStudents() {
        List<Student> frozen = studentRepository.findByStatus(StudentStatus.FROZEN);
        List<FrozenStudentResponse> result = new ArrayList<>();
        for (Student s : frozen) {
            StudentGroup last = studentGroupRepository.findByStudentIdOrderByJoinDateDesc(s.getId())
                .stream()
                .filter(sg -> "FROZEN".equals(sg.getExitReason()) || Boolean.FALSE.equals(sg.getIsActive()))
                .findFirst()
                .orElse(studentGroupRepository.findByStudentIdOrderByJoinDateDesc(s.getId())
                    .stream().findFirst().orElse(null));

            LocalDate frozenDate = last != null && last.getLeaveDate() != null
                ? last.getLeaveDate()
                : (s.getUpdatedAt() != null ? s.getUpdatedAt().toLocalDate() : null);

            result.add(FrozenStudentResponse.builder()
                .studentId(s.getId())
                .fullName(((s.getFirstName() != null ? s.getFirstName() : "")
                    + " " + (s.getLastName() != null ? s.getLastName() : "")).trim())
                .phone(s.getPhone())
                .frozenDate(frozenDate)
                .balance(s.getBalance() != null ? s.getBalance() : BigDecimal.ZERO)
                .lastGroupId(last != null && last.getGroup() != null ? last.getGroup().getId() : null)
                .lastGroupName(last != null && last.getGroup() != null ? last.getGroup().getGroupName() : null)
                .build());
        }
        return result;
    }
}
