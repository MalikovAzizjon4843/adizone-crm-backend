package com.crm.service;

import com.crm.dto.request.LeadAssignRequest;
import com.crm.dto.request.LeadCommentRequest;
import com.crm.dto.request.LeadRequest;
import com.crm.dto.response.LeadCommentResponse;
import com.crm.dto.response.LeadOperatorResponse;
import com.crm.dto.response.LeadOperatorStatsResponse;
import com.crm.dto.response.LeadResponse;
import com.crm.dto.response.LeadStatsResponse;
import com.crm.dto.response.PageResponse;
import com.crm.entity.Group;
import com.crm.entity.Lead;
import com.crm.entity.LeadComment;
import com.crm.entity.Student;
import com.crm.entity.StudentGroup;
import com.crm.entity.User;
import com.crm.entity.enums.LeadStatus;
import com.crm.entity.enums.MarketingSource;
import com.crm.entity.enums.PaymentStatus;
import com.crm.entity.enums.StudentStatus;
import com.crm.entity.enums.UserRole;
import com.crm.exception.BadRequestException;
import com.crm.exception.ResourceNotFoundException;
import com.crm.repository.GroupRepository;
import com.crm.repository.LeadCommentRepository;
import com.crm.repository.LeadRepository;
import com.crm.repository.StudentGroupRepository;
import com.crm.repository.StudentRepository;
import com.crm.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class LeadService {

    private static final String PAYMENT_COMMENT_TEXT = "To'lov qabul qilindi";

    private final LeadRepository leadRepository;
    private final LeadCommentRepository leadCommentRepository;
    private final StudentRepository studentRepository;
    private final GroupRepository groupRepository;
    private final StudentGroupRepository studentGroupRepository;
    private final PaymentScheduleService paymentScheduleService;
    private final UserRepository userRepository;

    @Transactional
    public LeadResponse createLead(LeadRequest request) {
        Lead lead = Lead.builder()
                .fullName(request.getFullName())
                .phone(request.getPhone())
                .address(request.getAddress())
                .course(request.getCourse())
                .format(request.getFormat() != null
                        ? request.getFormat().toUpperCase()
                        : "OFFLINE")
                .source(request.getSource() != null
                        ? request.getSource().toUpperCase()
                        : "WEBSITE")
                .notes(request.getNotes())
                .status(LeadStatus.NEW)
                .converted(false)
                .build();
        return toResponse(leadRepository.save(lead));
    }

    @Transactional(readOnly = true)
    public PageResponse<LeadResponse> getAll(
            int page, int size, String status, String search,
            Long assignedUserId, Boolean unassigned) {
        int safeSize = Math.min(Math.max(size, 1), 100);
        int safePage = Math.max(page, 0);
        Pageable pageable = PageRequest.of(safePage, safeSize, Sort.by("createdAt").descending());
        Specification<Lead> spec = buildLeadSpec(status, search, assignedUserId, unassigned);
        Page<Lead> leads = leadRepository.findAll(spec, pageable);
        return toPageResponse(leads);
    }

    @Transactional(readOnly = true)
    public LeadResponse getById(Long id) {
        return toResponse(leadRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Lead", id)));
    }

    @Transactional
    public LeadResponse assignLead(Long id, LeadAssignRequest request) {
        Lead lead = leadRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Lead", id));

        if (request.getUserId() == null) {
            lead.setAssignedUser(null);
            lead.setAssignedAt(null);
        } else {
            User user = userRepository.findById(request.getUserId())
                    .orElseThrow(() -> new ResourceNotFoundException("User", request.getUserId()));
            if (user.getRole() != UserRole.ADMIN && user.getRole() != UserRole.SUPER_ADMIN) {
                throw new BadRequestException("Faqat ADMIN yoki SUPER_ADMIN operator sifatida biriktiriladi");
            }
            lead.setAssignedUser(user);
            lead.setAssignedAt(LocalDateTime.now());
        }
        return toResponse(leadRepository.save(lead));
    }

    @Transactional
    public LeadResponse updateStatus(Long id, String status) {
        Lead lead = leadRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Lead", id));

        LeadStatus newStatus = parseStatus(status);
        lead.setStatus(newStatus);

        if (newStatus == LeadStatus.ONLINE_PAID || newStatus == LeadStatus.OFFLINE_PAID) {
            addSystemComment(lead, PAYMENT_COMMENT_TEXT);
        }

        return toResponse(leadRepository.save(lead));
    }

    @Transactional
    public LeadCommentResponse addComment(Long leadId, LeadCommentRequest request) {
        Lead lead = leadRepository.findById(leadId)
                .orElseThrow(() -> new ResourceNotFoundException("Lead", leadId));
        User author = getCurrentUser();
        if (author == null) {
            throw new BadRequestException("Foydalanuvchi aniqlanmadi");
        }

        LeadComment comment = LeadComment.builder()
                .lead(lead)
                .author(author)
                .text(request.getText().trim())
                .statusAtComment(lead.getStatus())
                .build();
        return toCommentResponse(leadCommentRepository.save(comment));
    }

    @Transactional(readOnly = true)
    public PageResponse<LeadCommentResponse> getComments(Long leadId, int page, int size) {
        if (!leadRepository.existsById(leadId)) {
            throw new ResourceNotFoundException("Lead", leadId);
        }
        int safeSize = Math.min(Math.max(size, 1), 100);
        int safePage = Math.max(page, 0);
        Pageable pageable = PageRequest.of(safePage, safeSize, Sort.by("createdAt").descending());
        Page<LeadComment> comments = leadCommentRepository.findByLeadIdOrderByCreatedAtDesc(leadId, pageable);
        return PageResponse.<LeadCommentResponse>builder()
                .content(comments.getContent().stream()
                        .map(this::toCommentResponse)
                        .collect(Collectors.toList()))
                .pageNumber(comments.getNumber())
                .pageSize(comments.getSize())
                .totalElements(comments.getTotalElements())
                .totalPages(comments.getTotalPages())
                .last(comments.isLast())
                .build();
    }

    @Transactional(readOnly = true)
    public List<LeadOperatorResponse> getOperators() {
        return userRepository.findByRoleInAndIsActiveTrueOrderByFirstNameAscLastNameAsc(
                        Arrays.asList(UserRole.ADMIN, UserRole.SUPER_ADMIN))
                .stream()
                .map(user -> LeadOperatorResponse.builder()
                        .id(user.getId())
                        .fullName(formatUserName(user))
                        .build())
                .collect(Collectors.toList());
    }

    @Transactional
    public LeadResponse convertToStudent(Long id) {
        return convertToStudent(id, null);
    }

    @Transactional
    public LeadResponse convertToStudent(Long id, Long groupId) {
        Lead lead = leadRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Lead", id));

        if (Boolean.TRUE.equals(lead.getConverted()) && lead.getStudent() != null) {
            lead.setStatus(LeadStatus.CONVERTED);
            return toResponse(leadRepository.save(lead));
        }

        String fullName = lead.getFullName() != null ? lead.getFullName().trim() : "";
        String firstName = fullName.isEmpty() ? "" : fullName.split("\\s+")[0];
        String lastName = fullName.contains(" ")
            ? fullName.substring(fullName.indexOf(' ') + 1).trim() : "";

        Student student = Student.builder()
                .firstName(firstName)
                .lastName(lastName)
                .phone(lead.getPhone())
                .status(StudentStatus.ACTIVE)
                .admissionDate(LocalDate.now())
                .admissionNumber("ADM-" + String.format("%05d",
                        studentRepository.count() + 1))
                .marketingSource(parseMarketingSource(lead.getSource()))
                .build();
        student = studentRepository.save(student);

        if (groupId != null) {
            Group group = groupRepository.findById(groupId).orElse(null);
            if (group != null) {
                LocalDate today = LocalDate.now();
                java.math.BigDecimal fee = group.getCourse() != null && group.getCourse().getMonthlyPrice() != null
                    ? group.getCourse().getMonthlyPrice() : java.math.BigDecimal.ZERO;
                student.setPaymentStartDate(today);
                student.setMonthlyFee(fee);
                student.setPaymentStatus(PaymentStatus.PENDING);
                student = studentRepository.save(student);

                StudentGroup sg = StudentGroup.builder()
                        .student(student)
                        .group(group)
                        .joinDate(today)
                        .paymentStartDate(today)
                        .nextPaymentDate(today)
                        .isTrial(false)
                        .isActive(true)
                        .monthlyPriceOverride(fee)
                        .paymentStatus("PENDING")
                        .lessonsAttended(0)
                        .build();
                studentGroupRepository.save(sg);
                paymentScheduleService.recalculateForStudent(student);
            }
        }

        lead.setStudent(student);
        lead.setConverted(true);
        lead.setStatus(LeadStatus.CONVERTED);
        return toResponse(leadRepository.save(lead));
    }

    @Transactional(readOnly = true)
    public LeadStatsResponse getStats() {
        Map<LeadStatus, Long> byStatus = new EnumMap<>(LeadStatus.class);
        for (LeadStatus status : LeadStatus.values()) {
            byStatus.put(status, 0L);
        }
        leadRepository.countByStatusGrouped().forEach(row -> {
            LeadStatus status = row[0] instanceof LeadStatus
                    ? (LeadStatus) row[0]
                    : LeadStatus.fromString(row[0] != null ? row[0].toString() : null);
            byStatus.put(status, (Long) row[1]);
        });

        List<LeadOperatorStatsResponse> byOperator = leadRepository.countByOperatorGrouped().stream()
                .map(row -> LeadOperatorStatsResponse.builder()
                        .userId((Long) row[0])
                        .name(row[1] != null ? row[1].toString().trim() : "")
                        .count((Long) row[2])
                        .converted(row[3] != null ? ((Number) row[3]).longValue() : 0L)
                        .build())
                .collect(Collectors.toList());

        return LeadStatsResponse.builder()
                .total(leadRepository.count())
                .byStatus(byStatus)
                .byOperator(byOperator)
                .unassigned(leadRepository.countByAssignedUserIsNull())
                .build();
    }

    @Transactional
    public Map<String, Object> migrateLeadStatuses() {
        Map<String, Integer> migrated = new LinkedHashMap<>();
        migrated.put("CONTACTED", leadRepository.migrateStatus("CONTACTED", "DAY_1_WORKED"));
        migrated.put("IN_PROGRESS", leadRepository.migrateStatus("IN_PROGRESS", "DAY_2_WORKED"));
        migrated.put("INTERESTED", leadRepository.migrateStatus("INTERESTED", "DAY_3_WORKED"));
        migrated.put("ENROLLED_CONVERTED", leadRepository.migrateEnrolledConverted());
        migrated.put("ENROLLED_ONLINE", leadRepository.migrateEnrolledOnline());
        migrated.put("ENROLLED_OFFLINE", leadRepository.migrateEnrolledOffline());

        Map<String, Object> result = new HashMap<>();
        result.put("migrated", migrated);
        result.put("totalUpdated", migrated.values().stream().mapToInt(Integer::intValue).sum());
        return result;
    }

    private Specification<Lead> buildLeadSpec(
            String status, String search, Long assignedUserId, Boolean unassigned) {
        Specification<Lead> spec = Specification.where(null);

        if (status != null && !status.isBlank()) {
            LeadStatus leadStatus = parseStatus(status);
            spec = spec.and((root, query, cb) -> cb.equal(root.get("status"), leadStatus));
        }
        if (assignedUserId != null) {
            spec = spec.and((root, query, cb) ->
                    cb.equal(root.get("assignedUser").get("id"), assignedUserId));
        }
        if (Boolean.TRUE.equals(unassigned)) {
            spec = spec.and((root, query, cb) -> cb.isNull(root.get("assignedUser")));
        }
        if (search != null && !search.isBlank()) {
            String term = "%" + search.trim().toLowerCase() + "%";
            String phoneTerm = "%" + search.trim() + "%";
            spec = spec.and((root, query, cb) -> cb.or(
                    cb.like(cb.lower(root.get("fullName")), term),
                    cb.like(root.get("phone"), phoneTerm)));
        }
        return spec;
    }

    private LeadStatus parseStatus(String status) {
        if (status == null || status.isBlank()) {
            throw new BadRequestException("Status majburiy");
        }
        try {
            return LeadStatus.valueOf(status.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("Noto'g'ri lead status: " + status);
        }
    }

    private void addSystemComment(Lead lead, String text) {
        User author = getCurrentUser();
        if (author == null) {
            return;
        }
        LeadComment comment = LeadComment.builder()
                .lead(lead)
                .author(author)
                .text(text)
                .statusAtComment(lead.getStatus())
                .build();
        leadCommentRepository.save(comment);
    }

    private User getCurrentUser() {
        String username = SecurityContextHolder.getContext().getAuthentication().getName();
        return userRepository.findByUsername(username).orElse(null);
    }

    private MarketingSource parseMarketingSource(String src) {
        if (src == null || src.isBlank()) {
            return MarketingSource.OTHER;
        }
        try {
            return MarketingSource.valueOf(src.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return MarketingSource.OTHER;
        }
    }

    private PageResponse<LeadResponse> toPageResponse(Page<Lead> leads) {
        List<Long> leadIds = leads.getContent().stream()
                .map(Lead::getId)
                .collect(Collectors.toList());
        Map<Long, Long> commentCounts = loadCommentCounts(leadIds);
        Map<Long, String> lastComments = loadLastCommentTexts(leadIds);

        return PageResponse.<LeadResponse>builder()
                .content(leads.getContent().stream()
                        .map(lead -> toResponse(
                                lead,
                                commentCounts.getOrDefault(lead.getId(), 0L),
                                lastComments.get(lead.getId())))
                        .collect(Collectors.toList()))
                .pageNumber(leads.getNumber())
                .pageSize(leads.getSize())
                .totalElements(leads.getTotalElements())
                .totalPages(leads.getTotalPages())
                .last(leads.isLast())
                .build();
    }

    private Map<Long, Long> loadCommentCounts(List<Long> leadIds) {
        if (leadIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, Long> counts = new HashMap<>();
        leadCommentRepository.countByLeadIds(leadIds).forEach(row ->
                counts.put((Long) row[0], (Long) row[1]));
        return counts;
    }

    private Map<Long, String> loadLastCommentTexts(List<Long> leadIds) {
        if (leadIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> texts = new HashMap<>();
        leadCommentRepository.findLatestTextByLeadIds(leadIds).forEach(row ->
                texts.put(((Number) row[0]).longValue(), row[1] != null ? row[1].toString() : null));
        return texts;
    }

    private LeadResponse toResponse(Lead lead) {
        long commentsCount = leadCommentRepository.countByLeadId(lead.getId());
        String lastCommentText = null;
        List<LeadComment> latest = leadCommentRepository.findByLeadIdOrderByCreatedAtDesc(lead.getId());
        if (!latest.isEmpty()) {
            lastCommentText = latest.get(0).getText();
        }
        return toResponse(lead, commentsCount, lastCommentText);
    }

    private LeadResponse toResponse(Lead lead, long commentsCount, String lastCommentText) {
        String studentName = null;
        if (lead.getStudent() != null) {
            studentName = (lead.getStudent().getFirstName() != null ? lead.getStudent().getFirstName() : "")
                    + " "
                    + (lead.getStudent().getLastName() != null ? lead.getStudent().getLastName() : "");
            studentName = studentName.trim();
        }
        return LeadResponse.builder()
                .id(lead.getId())
                .uuid(lead.getUuid())
                .fullName(lead.getFullName())
                .phone(lead.getPhone())
                .address(lead.getAddress())
                .course(lead.getCourse())
                .format(lead.getFormat())
                .status(lead.getStatus())
                .source(lead.getSource())
                .notes(lead.getNotes())
                .converted(lead.getConverted())
                .studentId(lead.getStudent() != null ? lead.getStudent().getId() : null)
                .studentName(studentName != null && !studentName.isEmpty() ? studentName : null)
                .assignedUserId(lead.getAssignedUser() != null ? lead.getAssignedUser().getId() : null)
                .assignedUserName(lead.getAssignedUser() != null
                        ? formatUserName(lead.getAssignedUser()) : null)
                .assignedAt(lead.getAssignedAt())
                .commentsCount(commentsCount)
                .lastCommentText(lastCommentText)
                .createdAt(lead.getCreatedAt())
                .updatedAt(lead.getUpdatedAt())
                .build();
    }

    private LeadCommentResponse toCommentResponse(LeadComment comment) {
        return LeadCommentResponse.builder()
                .id(comment.getId())
                .leadId(comment.getLead().getId())
                .authorId(comment.getAuthor().getId())
                .authorFullName(formatUserName(comment.getAuthor()))
                .text(comment.getText())
                .statusAtComment(comment.getStatusAtComment())
                .createdAt(comment.getCreatedAt())
                .build();
    }

    private String formatUserName(User user) {
        return ((user.getFirstName() != null ? user.getFirstName() : "")
                + " "
                + (user.getLastName() != null ? user.getLastName() : "")).trim();
    }
}
