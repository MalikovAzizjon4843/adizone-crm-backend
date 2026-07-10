package com.crm.service;

import com.crm.dto.request.ContractCreateDto;
import com.crm.dto.request.ContractTemplateCreateDto;
import com.crm.dto.response.ContractDto;
import com.crm.dto.response.ContractTemplateDto;
import com.crm.dto.response.PageResponse;
import com.crm.entity.*;
import com.crm.entity.enums.ContractStatus;
import com.crm.entity.enums.ContractType;
import com.crm.exception.BadRequestException;
import com.crm.exception.ResourceNotFoundException;
import com.crm.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ContractService {

    private static final String CENTER_NAME = "Adizone";
    private static final DateTimeFormatter DATE_FORMAT =
        DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private final ContractTemplateRepository contractTemplateRepository;
    private final ContractRepository contractRepository;
    private final StudentRepository studentRepository;
    private final StudentGroupRepository studentGroupRepository;
    private final ParentRepository parentRepository;

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

    @Transactional
    public ContractTemplateDto createTemplate(ContractTemplateCreateDto dto) {
        ContractTemplate template = new ContractTemplate();
        applyTemplateDto(template, dto);
        if (Boolean.TRUE.equals(dto.getIsDefault())) {
            clearDefaultTemplate();
        }
        return toTemplateDto(contractTemplateRepository.save(template));
    }

    @Transactional
    public ContractTemplateDto updateTemplate(Long id, ContractTemplateCreateDto dto) {
        ContractTemplate template = findTemplateById(id);
        applyTemplateDto(template, dto);
        if (Boolean.TRUE.equals(dto.getIsDefault())) {
            clearDefaultTemplateExcept(id);
        }
        return toTemplateDto(contractTemplateRepository.save(template));
    }

    @Transactional
    public void deleteTemplate(Long id) {
        contractTemplateRepository.delete(findTemplateById(id));
    }

    @Transactional(readOnly = true)
    public PageResponse<ContractDto> getAll(Long studentId, String status, Pageable pageable) {
        ContractStatus statusFilter = parseStatus(status);
        Specification<Contract> spec = buildContractSpec(studentId, statusFilter);
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

    @Transactional
    public ContractDto generateForStudent(ContractCreateDto dto) {
        if (dto.getStudentId() == null) {
            throw new BadRequestException("studentId ko'rsatilishi shart");
        }

        Student student = studentRepository.findById(dto.getStudentId())
            .orElseThrow(() -> new ResourceNotFoundException("Student", dto.getStudentId()));

        ContractTemplate template = resolveTemplate(dto.getTemplateId());
        String contractNumber = generateNumber();
        String rendered = renderContent(template.getContent(), student, contractNumber);

        Contract contract = new Contract();
        contract.setContractNumber(contractNumber);
        contract.setStudent(student);
        contract.setTemplate(template);
        contract.setType(template.getType());
        contract.setRenderedContent(rendered);
        contract.setStatus(ContractStatus.DRAFT);
        contract.setContractDate(LocalDate.now());

        return toContractDto(contractRepository.save(contract));
    }

    @Transactional
    public ContractDto acceptOffer(Long contractId) {
        Contract contract = findContractById(contractId);
        if (contract.getType() != ContractType.OFFER) {
            throw new BadRequestException("Faqat OFFER shartnomalar qabul qilinadi");
        }
        contract.setOfferAccepted(true);
        contract.setStatus(ContractStatus.ACCEPTED);
        contract.setAcceptedAt(LocalDateTime.now());
        return toContractDto(contractRepository.save(contract));
    }

    @Transactional
    public ContractDto markSigned(Long contractId) {
        Contract contract = findContractById(contractId);
        contract.setStatus(ContractStatus.SIGNED);
        return toContractDto(contractRepository.save(contract));
    }

    @Transactional
    public void deleteContract(Long id) {
        contractRepository.delete(findContractById(id));
    }

    private ContractTemplate resolveTemplate(Long templateId) {
        if (templateId != null) {
            return findTemplateById(templateId);
        }
        return contractTemplateRepository.findByIsDefaultTrue()
            .orElseThrow(() -> new BadRequestException("Standart shartnoma shabloni topilmadi"));
    }

    private String generateNumber() {
        long seq = contractRepository.count() + 1;
        return "CTR-" + String.format("%04d", seq);
    }

    private String renderContent(String templateContent, Student student, String contractNumber) {
        String content = templateContent != null ? templateContent : "";
        Map<String, String> placeholders = buildPlaceholders(student, contractNumber);
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            content = content.replace(entry.getKey(), entry.getValue());
        }
        return content;
    }

    private Map<String, String> buildPlaceholders(Student student, String contractNumber) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("{{studentName}}", nullSafe(student.getFirstName()) + " " + nullSafe(student.getLastName()));
        values.put("{{studentPhone}}", nullSafe(student.getPhone()));
        values.put("{{studentPassport}}", "");
        values.put("{{contractNumber}}", nullSafe(contractNumber));
        values.put("{{centerName}}", CENTER_NAME);
        values.put("{{currentDate}}", LocalDate.now().format(DATE_FORMAT));

        StudentGroup enrollment = resolveActiveEnrollment(student.getId());
        if (enrollment != null && enrollment.getGroup() != null) {
            Group group = enrollment.getGroup();
            values.put("{{groupName}}", nullSafe(group.getGroupName()));
            if (group.getCourse() != null) {
                values.put("{{courseName}}", nullSafe(group.getCourse().getCourseName()));
                values.put("{{monthlyFee}}", formatAmount(resolveMonthlyFee(enrollment, group)));
            } else {
                values.put("{{courseName}}", "");
                values.put("{{monthlyFee}}", "");
            }
            values.put("{{startDate}}", enrollment.getJoinDate() != null
                ? enrollment.getJoinDate().format(DATE_FORMAT) : "");
        } else {
            values.put("{{groupName}}", "");
            values.put("{{courseName}}", "");
            values.put("{{monthlyFee}}", "");
            values.put("{{startDate}}", student.getAdmissionDate() != null
                ? student.getAdmissionDate().format(DATE_FORMAT) : "");
        }

        Parent parent = resolveParent(student.getId());
        if (parent != null) {
            values.put("{{parentName}}", nullSafe(parent.getFullName()));
            values.put("{{parentPhone}}", nullSafe(parent.getPhone()));
        } else {
            values.put("{{parentName}}", "");
            values.put("{{parentPhone}}", nullSafe(student.getParentPhone()));
        }
        return values;
    }

    private StudentGroup resolveActiveEnrollment(Long studentId) {
        try {
            return studentGroupRepository.findByStudentIdAndIsActiveTrue(studentId).stream()
                .findFirst()
                .orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    private Parent resolveParent(Long studentId) {
        try {
            List<Parent> parents = parentRepository.findByStudentId(studentId);
            return parents.isEmpty() ? null : parents.get(0);
        } catch (Exception e) {
            return null;
        }
    }

    private static BigDecimal resolveMonthlyFee(StudentGroup enrollment, Group group) {
        if (enrollment.getMonthlyPriceOverride() != null) {
            return enrollment.getMonthlyPriceOverride();
        }
        if (group.getCourse() != null && group.getCourse().getMonthlyPrice() != null) {
            return group.getCourse().getMonthlyPrice();
        }
        return BigDecimal.ZERO;
    }

    private static String formatAmount(BigDecimal amount) {
        if (amount == null) {
            return "";
        }
        return amount.stripTrailingZeros().toPlainString();
    }

    private static String nullSafe(String value) {
        return value != null ? value : "";
    }

    private void applyTemplateDto(ContractTemplate template, ContractTemplateCreateDto dto) {
        if (dto.getTitle() != null) {
            template.setTitle(dto.getTitle());
        }
        if (dto.getType() != null) {
            template.setType(dto.getType());
        }
        if (dto.getContent() != null) {
            template.setContent(dto.getContent());
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

    private static Specification<Contract> buildContractSpec(Long studentId, ContractStatus status) {
        Specification<Contract> spec = Specification.where(null);
        if (studentId != null) {
            spec = spec.and((root, query, cb) ->
                cb.equal(root.get("student").get("id"), studentId));
        }
        if (status != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("status"), status));
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

    private Contract findContractById(Long id) {
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
