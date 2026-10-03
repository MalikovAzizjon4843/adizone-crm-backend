package com.crm.service;

import com.crm.audit.AuditAction;
import com.crm.audit.AuditContext;
import com.crm.audit.Audited;
import com.crm.billing.EnrollmentPricing;
import com.crm.dto.request.ContractCreateDto;
import com.crm.dto.request.ContractTemplateCreateDto;
import com.crm.dto.response.ContractDto;
import com.crm.dto.response.ContractTemplateDto;
import com.crm.dto.response.PageResponse;
import com.crm.entity.*;
import com.crm.entity.enums.ContractStatus;
import com.crm.entity.enums.ContractType;
import com.crm.entity.enums.PaymentType;
import com.crm.exception.BadRequestException;
import com.crm.exception.CodedException;
import com.crm.exception.ConflictException;
import com.crm.exception.ResourceNotFoundException;
import com.crm.repository.*;
import com.crm.util.ContractHtml;
import com.crm.util.SearchSpecs;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Join;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.stream.Collectors;

/**
 * Shartnomalar — leaves-exams-contracts §6.
 *
 * <ul>
 *   <li>Generatsiyada narx snapshot'i ({@link EnrollmentPricing}) muzlatiladi — keyin narx o'zgarsa
 *       shartnoma o'zgarmaydi;</li>
 *   <li>shablon saqlanayotganda tozalanadi ({@link ContractHtml}) va noma'lum belgi rad etiladi;</li>
 *   <li>belgilar qiymati HTML-escape bilan qo'yiladi, rekvizit yo'q bo'lsa {@code ________};</li>
 *   <li>holatlar: DRAFT → SIGNED (OFFLINE) / ACCEPTED (OFFER) → CANCELLED; imzolangan o'chirilmaydi.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class ContractService {

    private static final DateTimeFormatter DATE_FORMAT =
        DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private final ContractTemplateRepository contractTemplateRepository;
    private final ContractRepository contractRepository;
    private final StudentRepository studentRepository;
    private final StudentGroupRepository studentGroupRepository;
    private final ParentRepository parentRepository;
    private final ContractNumberService contractNumberService;
    private final CenterSettingsService centerSettingsService;
    private final ContractPdfService contractPdfService;
    private final TeacherAccessService teacherAccessService;
    /** Shartnoma sanasi — Asia/Tashkent bo'yicha (testda boshqariladigan soat). */
    private final Clock billingClock;

    // ── Shablonlar ──────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<ContractTemplateDto> getAllTemplates() {
        return contractTemplateRepository.findAll().stream()
            .map(this::toTemplateDto)
            .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public ContractTemplateDto getTemplate(Long id) {
        return toTemplateDto(findTemplateById(id));
    }

    public List<ContractPlaceholders.Placeholder> placeholders() {
        return ContractPlaceholders.ALL;
    }

    @Transactional
    @Audited(action = AuditAction.CREATE, entity = "ContractTemplate",
        summary = "'Shartnoma shabloni yaratildi: ' + #result.title", entityId = "#result.id")
    public ContractTemplateDto createTemplate(ContractTemplateCreateDto dto) {
        ContractTemplate template = new ContractTemplate();
        applyTemplateDto(template, dto);
        if (Boolean.TRUE.equals(dto.getIsDefault())) {
            clearDefaultTemplate();
        }
        return toTemplateDto(contractTemplateRepository.save(template));
    }

    @Transactional
    @Audited(action = AuditAction.UPDATE, entity = "ContractTemplate",
        summary = "'Shartnoma shabloni yangilandi: ' + #result.title", entityId = "#id")
    public ContractTemplateDto updateTemplate(Long id, ContractTemplateCreateDto dto) {
        ContractTemplate template = findTemplateById(id);
        applyTemplateDto(template, dto);
        if (Boolean.TRUE.equals(dto.getIsDefault())) {
            clearDefaultTemplateExcept(id);
        }
        return toTemplateDto(contractTemplateRepository.save(template));
    }

    @Transactional
    @Audited(action = AuditAction.DELETE, entity = "ContractTemplate",
        summary = "'Shartnoma shabloni o''chirildi: #' + #id", entityId = "#id")
    public void deleteTemplate(Long id) {
        contractTemplateRepository.delete(findTemplateById(id));
    }

    // ── Shartnomalar: o'qish ────────────────────────────────────────────

    /**
     * {@code GET /api/contracts} filtri. {@code q} — o'quvchi ismi/familiyasi ("Ism Familiya" ham), telefoni
     * (o'quvchi yoki ota-ona) yoki shartnoma raqami bo'yicha; {@code %} va {@code _} oddiy belgi.
     * {@code from}/{@code to} — shartnoma sanasi (ikkalasi kiritilgan).
     */
    public record ContractFilter(Long studentId, String status, String q, LocalDate from, LocalDate to) {
    }

    @Transactional(readOnly = true)
    public PageResponse<ContractDto> getAll(ContractFilter filter, Pageable pageable) {
        if (filter.from() != null && filter.to() != null && filter.to().isBefore(filter.from())) {
            throw CodedException.badRequest("contract.dates.invalid");
        }
        ContractStatus statusFilter = parseStatus(filter.status());
        Specification<Contract> spec = buildContractSpec(filter, statusFilter);
        Page<Contract> page = contractRepository.findAll(spec, pageable);

        return PageResponse.<ContractDto>builder()
            .content(page.getContent().stream().map(this::toContractDto).toList())
            .pageNumber(page.getNumber())
            .pageSize(page.getSize())
            .totalElements(page.getTotalElements())
            .totalPages(page.getTotalPages())
            .last(page.isLast())
            .build();
    }

    @Transactional(readOnly = true)
    public ContractDto getById(Long id) {
        return toContractDto(findContractById(id));
    }

    @Transactional(readOnly = true)
    public List<ContractDto> getByStudent(Long studentId) {
        return contractRepository.findByStudentId(studentId).stream()
            .map(this::toContractDto)
            .collect(Collectors.toList());
    }

    // ── Generatsiya (§6.1) ──────────────────────────────────────────────

    @Transactional
    @Audited(action = AuditAction.CREATE, entity = "Contract",
        summary = "'Shartnoma yaratildi: ' + #result.contractNumber + ' — ' + #result.studentName",
        entityId = "#result.id", label = "#result.contractNumber")
    public ContractDto generateForStudent(ContractCreateDto dto) {
        if (dto.getStudentId() == null) {
            throw new BadRequestException("studentId ko'rsatilishi shart");
        }

        Student student = studentRepository.findById(dto.getStudentId())
            .orElseThrow(() -> new ResourceNotFoundException("Student", dto.getStudentId()));
        StudentGroup enrollment = resolveEnrollment(student, dto.getStudentGroupId());

        ContractTemplate template = resolveTemplate(dto.getTemplateId());
        LocalDate contractDate = LocalDate.now(billingClock);
        String contractNumber = generateNumber(contractDate);

        Contract contract = new Contract();
        contract.setContractNumber(contractNumber);
        contract.setStudent(student);
        contract.setTemplate(template);
        contract.setType(template.getType());
        contract.setStatus(ContractStatus.DRAFT);
        contract.setContractDate(contractDate);
        applySnapshot(contract, student, enrollment);
        contract.setRenderedContent(render(template.getContent(), contract));

        return toContractDto(contractRepository.save(contract));
    }

    /**
     * Narx olinadigan yozilma: berilgan bo'lsa — shu o'quvchiniki bo'lishi shart; berilmasa —
     * yagona faol yozilma, bir nechta bo'lsa 400, umuman yo'q bo'lsa {@code null} (narxsiz shartnoma).
     */
    private StudentGroup resolveEnrollment(Student student, Long studentGroupId) {
        if (studentGroupId != null) {
            StudentGroup sg = studentGroupRepository.findById(studentGroupId)
                .orElseThrow(() -> new ResourceNotFoundException("StudentGroup", studentGroupId));
            if (sg.getStudent() == null || !student.getId().equals(sg.getStudent().getId())) {
                throw CodedException.badRequest("contract.studentGroup.mismatch", studentGroupId);
            }
            return sg;
        }
        List<StudentGroup> active = studentGroupRepository.findActiveByStudentId(student.getId());
        if (active.size() > 1) {
            throw CodedException.badRequest("contract.studentGroupRequired")
                .withData(Map.of("studentGroups", active.stream().map(sg -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("studentGroupId", sg.getId());
                    m.put("groupName", sg.getGroup() != null ? sg.getGroup().getGroupName() : null);
                    return m;
                }).toList()));
        }
        return active.isEmpty() ? null : active.get(0);
    }

    /** §6.1: MONTHLY — oylik narx va {@code c(sg)}; PER_LESSON — dars narxi va {@code l(sg)}. */
    static void applySnapshot(Contract c, Student student, StudentGroup sg) {
        if (sg == null) {
            c.setStartDate(student.getAdmissionDate());
            return;
        }
        PaymentType type = sg.getPaymentType() != null ? sg.getPaymentType() : PaymentType.MONTHLY;
        BigDecimal list = type == PaymentType.PER_LESSON
            ? EnrollmentPricing.lessonPrice(sg) : EnrollmentPricing.monthlyFee(sg);
        BigDecimal discount;
        try {
            discount = EnrollmentPricing.discount(sg);
        } catch (IllegalArgumentException e) {
            throw CodedException.badRequest("student.discount.invalid");
        }
        BigDecimal fin = type == PaymentType.PER_LESSON
            ? EnrollmentPricing.effectiveLessonPrice(sg) : EnrollmentPricing.effectiveMonthlyFee(sg);
        c.setStudentGroup(sg);
        c.setPaymentType(type);
        c.setListPrice(list);
        c.setDiscountPercent(discount);
        c.setFinalAmount(fin);
        c.setDiscountAmount(list.subtract(fin));
        c.setStartDate(sg.getPaymentStartDate() != null ? sg.getPaymentStartDate() : sg.getJoinDate());
    }

    // ── Holatlar (§6.2) ─────────────────────────────────────────────────

    @Transactional
    @Audited(action = AuditAction.STATUS_CHANGE, entity = "Contract",
        summary = "'Taklif qabul qilindi: ' + #result.contractNumber", entityId = "#contractId")
    public ContractDto acceptOffer(Long contractId) {
        Contract contract = findContractById(contractId);
        if (contract.getType() != ContractType.OFFER) {
            throw new BadRequestException("Faqat OFFER shartnomalar qabul qilinadi");
        }
        requireDraft(contract);
        contract.setOfferAccepted(true);
        contract.setStatus(ContractStatus.ACCEPTED);
        contract.setAcceptedAt(LocalDateTime.now(billingClock));
        AuditContext.change("status", ContractStatus.DRAFT, ContractStatus.ACCEPTED);
        contractPdfService.freeze(contract);
        return toContractDto(contractRepository.save(contract));
    }

    /** DRAFT → SIGNED (OFFLINE). Imzo paytidagi PDF muzlatiladi — keyin rekvizit o'zgarsa ham o'zgarmaydi. */
    @Transactional
    @Audited(action = AuditAction.STATUS_CHANGE, entity = "Contract",
        summary = "'Shartnoma imzolandi: ' + #result.contractNumber", entityId = "#contractId")
    public ContractDto markSigned(Long contractId) {
        Contract contract = findContractById(contractId);
        requireDraft(contract);
        if (contract.getType() == ContractType.OFFER) {
            throw CodedException.badRequest("contract.sign.offer");
        }
        contract.setStatus(ContractStatus.SIGNED);
        contract.setSignedAt(LocalDateTime.now(billingClock));
        contract.setSignedBy(teacherAccessService.getCurrentUserOrThrow());
        AuditContext.change("status", ContractStatus.DRAFT, ContractStatus.SIGNED);
        contractPdfService.freeze(contract);
        return toContractDto(contractRepository.save(contract));
    }

    @Transactional
    @Audited(action = AuditAction.STATUS_CHANGE, entity = "Contract",
        summary = "'Shartnoma bekor qilindi: ' + #result.contractNumber + ' — ' + #result.cancelReason",
        entityId = "#contractId")
    public ContractDto cancel(Long contractId, String reason) {
        String why = reason != null ? reason.trim() : "";
        if (why.length() < 3 || why.length() > 500) {
            throw CodedException.badRequest("contract.cancel.reasonRequired");
        }
        Contract contract = findContractById(contractId);
        if (contract.getStatus() == ContractStatus.CANCELLED) {
            throw new ConflictException("contract.alreadyCancelled");
        }
        AuditContext.change("status", contract.getStatus(), ContractStatus.CANCELLED);
        contract.setStatus(ContractStatus.CANCELLED);
        contract.setCancelledAt(LocalDateTime.now(billingClock));
        contract.setCancelledBy(teacherAccessService.getCurrentUserOrThrow());
        contract.setCancelReason(why);
        return toContractDto(contractRepository.save(contract));
    }

    /** Faqat imzolanmagan (DRAFT yoki DRAFT dan bekor qilingan); raqam qaytmaydi (C-01). */
    @Transactional
    @Audited(action = AuditAction.DELETE, entity = "Contract",
        summary = "'Shartnoma o''chirildi: #' + #id", entityId = "#id")
    public void deleteContract(Long id) {
        Contract contract = findContractById(id);
        boolean everSigned = contract.getStatus() == ContractStatus.SIGNED
            || contract.getStatus() == ContractStatus.ACCEPTED
            || contract.getSignedAt() != null || contract.getAcceptedAt() != null;
        if (everSigned) {
            throw new ConflictException("contract.signed");
        }
        contractRepository.delete(contract);
    }

    // ── PDF va chop etish (§6.3) ────────────────────────────────────────

    @Transactional
    public ContractPdfService.Pdf pdf(Long id) {
        return contractPdfService.pdf(findContractById(id));
    }

    @Transactional(readOnly = true)
    public String printHtml(Long id) {
        return contractPdfService.html(findContractById(id));
    }

    // ── Belgilar ────────────────────────────────────────────────────────

    /** Tozalangan shablonga belgilar qiymatini (escape bilan) qo'yadi; katalogda yo'q belgi o'zgarmaydi. */
    String render(String templateContent, Contract contract) {
        String safe = ContractHtml.sanitize(templateContent);
        Map<String, String> values = placeholderValues(contract);
        Matcher m = ContractPlaceholders.PATTERN.matcher(safe);
        StringBuilder out = new StringBuilder(safe.length() + 64);
        while (m.find()) {
            String value = values.get(m.group(1));
            m.appendReplacement(out, Matcher.quoteReplacement(value != null ? ContractHtml.escape(value) : m.group()));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** Belgi → qiymat (escape qilinmagan). Rekvizit bo'sh bo'lsa {@link ContractPlaceholders#BLANK}. */
    Map<String, String> placeholderValues(Contract c) {
        Student student = c.getStudent();
        Map<String, String> values = new LinkedHashMap<>();
        String date = c.getContractDate() != null ? c.getContractDate().format(DATE_FORMAT) : "";
        values.put("contractNumber", nullSafe(c.getContractNumber()));
        values.put("contractDate", date);
        values.put("currentDate", date);
        values.put("studentName", (nullSafe(student.getFirstName()) + " " + nullSafe(student.getLastName())).trim());
        values.put("studentPhone", nullSafe(student.getPhone()));
        values.put("studentPassport", ContractPlaceholders.BLANK);

        StudentGroup sg = c.getStudentGroup();
        Group group = sg != null ? sg.getGroup() : null;
        values.put("groupName", group != null ? nullSafe(group.getGroupName()) : "");
        values.put("courseName", group != null && group.getCourse() != null
            ? nullSafe(group.getCourse().getCourseName()) : "");
        values.put("paymentType", c.getPaymentType() == null ? ""
            : c.getPaymentType() == PaymentType.PER_LESSON ? "darsbay" : "oylik");
        values.put("coursePrice", formatAmount(c.getListPrice()));
        values.put("discountPercent", formatPercent(c.getDiscountPercent()));
        values.put("discountAmount", formatAmount(c.getDiscountAmount()));
        values.put("finalAmount", formatAmount(c.getFinalAmount()));
        values.put("monthlyFee", formatAmount(c.getFinalAmount()));
        values.put("startDate", c.getStartDate() != null ? c.getStartDate().format(DATE_FORMAT) : "");

        Parent parent = resolveParent(student.getId());
        if (parent != null) {
            values.put("parentName", nullSafe(parent.getFullName()));
            values.put("parentPhone", nullSafe(parent.getPhone()));
        } else {
            values.put("parentName", "");
            values.put("parentPhone", nullSafe(student.getParentPhone()));
        }

        Map<String, String> center = centerSettingsService.values();
        center.forEach((field, value) -> values.put(CenterSettingsService.PREFIX + field,
            value.isBlank() ? ContractPlaceholders.BLANK : value));
        values.put("centerName", center.get("shortName").isBlank() ? ContractPlaceholders.BLANK : center.get("shortName"));
        return values;
    }

    private Parent resolveParent(Long studentId) {
        List<Parent> parents = parentRepository.findByStudentId(studentId);
        return parents.isEmpty() ? null : parents.get(0);
    }

    // ── yordamchilar ────────────────────────────────────────────────────

    private ContractTemplate resolveTemplate(Long templateId) {
        if (templateId != null) {
            return findTemplateById(templateId);
        }
        return contractTemplateRepository.findByIsDefaultTrue()
            .orElseThrow(() -> new BadRequestException("Standart shartnoma shabloni topilmadi"));
    }

    /**
     * Shartnoma raqami — {@code CTR-YYYY-NNNNN}, har yil 00001 dan (phase5-audit C-01, Q8).
     *
     * <p>Avval {@code count() + 1} edi: bitta shartnoma o'chirilgach keyingi raqam
     * mavjudiga to'g'ri kelib, har {@code generate} UNIQUE buzilishi bilan 500
     * qaytarardi. Endi yil bo'yicha hisoblagich ({@link ContractNumberService}, V59) —
     * qulf ostida, takrorlanmaydi. {@code YYYY} — shartnoma sanasining yili.
     * Eski {@code CTR-0001} raqamlari o'zgarmaydi — boshqa ko'rinishda, to'qnashmaydi.
     */
    private String generateNumber(LocalDate contractDate) {
        return contractNumberService.next(contractDate.getYear());
    }

    private static void requireDraft(Contract c) {
        if (c.getStatus() != ContractStatus.DRAFT) {
            throw new ConflictException("contract.notDraft", c.getStatus());
        }
    }

    /** 1200000 → "1 200 000" (bo'sh joy bilan guruhlash, kasrsiz bo'lsa kasr yo'q). */
    static String formatAmount(BigDecimal amount) {
        if (amount == null) {
            return "";
        }
        DecimalFormatSymbols symbols = new DecimalFormatSymbols(Locale.ROOT);
        symbols.setGroupingSeparator(' ');
        symbols.setDecimalSeparator('.');
        return new DecimalFormat("#,##0.##", symbols).format(amount);
    }

    private static String formatPercent(BigDecimal percent) {
        if (percent == null) {
            return "";
        }
        return percent.stripTrailingZeros().toPlainString();
    }

    private static String nullSafe(String value) {
        return value != null ? value : "";
    }

    /** Saqlashda: tozalash (C-07) va noma'lum belgilar — 400. */
    private void applyTemplateDto(ContractTemplate template, ContractTemplateCreateDto dto) {
        if (dto.getTitle() != null) {
            template.setTitle(dto.getTitle());
        }
        if (dto.getType() != null) {
            template.setType(dto.getType());
        }
        if (dto.getContent() != null) {
            Set<String> unknown = ContractPlaceholders.unknown(dto.getContent());
            if (!unknown.isEmpty()) {
                throw CodedException.badRequest("contract.template.unknownPlaceholder", String.join(", ", unknown))
                    .withData(Map.of("unknown", List.copyOf(unknown)));
            }
            template.setContent(ContractHtml.sanitize(dto.getContent()));
        }
        if (dto.getIsDefault() != null) {
            template.setDefault(dto.getIsDefault());
        }
    }

    private void clearDefaultTemplate() {
        contractTemplateRepository.findByIsDefaultTrue().ifPresent(t -> {
            t.setDefault(false);
            contractTemplateRepository.save(t);
        });
    }

    private void clearDefaultTemplateExcept(Long id) {
        contractTemplateRepository.findByIsDefaultTrue().ifPresent(t -> {
            if (!t.getId().equals(id)) {
                t.setDefault(false);
                contractTemplateRepository.save(t);
            }
        });
    }

    private static Specification<Contract> buildContractSpec(ContractFilter f, ContractStatus status) {
        Specification<Contract> spec = Specification.where(null);
        if (f.studentId() != null) {
            spec = spec.and((root, query, cb) ->
                cb.equal(root.get("student").get("id"), f.studentId()));
        }
        if (status != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("status"), status));
        }
        String q = SearchSpecs.normalize(f.q());
        if (q != null) {
            String pattern = SearchSpecs.containsPattern(q);
            spec = spec.and((root, query, cb) -> {
                Join<Contract, Student> s = root.join("student");
                Expression<String> fullName = cb.concat(cb.concat(s.get("firstName"), " "), s.get("lastName"));
                return cb.or(
                    SearchSpecs.containsIgnoreCase(cb, root.get("contractNumber"), pattern),
                    SearchSpecs.containsIgnoreCase(cb, fullName, pattern),
                    SearchSpecs.containsIgnoreCase(cb, s.get("phone"), pattern),
                    SearchSpecs.containsIgnoreCase(cb, s.get("parentPhone"), pattern));
            });
        }
        if (f.from() != null) {
            spec = spec.and((root, query, cb) -> cb.greaterThanOrEqualTo(root.get("contractDate"), f.from()));
        }
        if (f.to() != null) {
            spec = spec.and((root, query, cb) -> cb.lessThanOrEqualTo(root.get("contractDate"), f.to()));
        }
        return spec;
    }

    private static ContractStatus parseStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return ContractStatus.valueOf(status.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private ContractTemplate findTemplateById(Long id) {
        return contractTemplateRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("ContractTemplate", id));
    }

    Contract findContractById(Long id) {
        return contractRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Contract", id));
    }

    private ContractTemplateDto toTemplateDto(ContractTemplate t) {
        return ContractTemplateDto.builder()
            .id(t.getId())
            .uuid(t.getUuid())
            .title(t.getTitle())
            .type(t.getType())
            .content(t.getContent())
            .isDefault(t.isDefault())
            .createdAt(t.getCreatedAt())
            .build();
    }

    private ContractDto toContractDto(Contract c) {
        StudentGroup sg = c.getStudentGroup();
        Group group = sg != null ? sg.getGroup() : null;
        ContractDto dto = ContractDto.builder()
            .id(c.getId())
            .uuid(c.getUuid())
            .contractNumber(c.getContractNumber())
            .type(c.getType())
            .renderedContent(c.getRenderedContent())
            .status(c.getStatus())
            .offerAccepted(c.isOfferAccepted())
            .acceptedAt(c.getAcceptedAt())
            .contractDate(c.getContractDate())
            .createdAt(c.getCreatedAt())
            .studentGroupId(sg != null ? sg.getId() : null)
            .groupName(group != null ? group.getGroupName() : null)
            .courseName(group != null && group.getCourse() != null ? group.getCourse().getCourseName() : null)
            .paymentType(c.getPaymentType())
            .listPrice(c.getListPrice())
            .discountPercent(c.getDiscountPercent())
            .discountAmount(c.getDiscountAmount())
            .finalAmount(c.getFinalAmount())
            .startDate(c.getStartDate())
            .signedAt(c.getSignedAt())
            .signedByName(c.getSignedBy() != null ? SalaryCalculationService.fullName(c.getSignedBy()) : null)
            .cancelledAt(c.getCancelledAt())
            .cancelReason(c.getCancelReason())
            .hasPdf(c.getPdfFile() != null)
            .missingRequisites(centerSettingsService.missing())
            .build();

        if (c.getStudent() != null) {
            dto.setStudentId(c.getStudent().getId());
            dto.setStudentName(c.getStudent().getFirstName() + " " + c.getStudent().getLastName());
        }
        if (c.getTemplate() != null) {
            dto.setTemplateId(c.getTemplate().getId());
            dto.setTemplateTitle(c.getTemplate().getTitle());
        }
        return dto;
    }
}
