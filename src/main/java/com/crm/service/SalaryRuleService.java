package com.crm.service;

import com.crm.dto.request.SalaryRuleRequest;
import com.crm.dto.response.SalaryRuleResponse;
import com.crm.entity.SalaryRule;
import com.crm.entity.User;
import com.crm.exception.ResourceNotFoundException;
import com.crm.repository.SalaryRuleRepository;
import com.crm.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

@Service
@RequiredArgsConstructor
public class SalaryRuleService {

    private final SalaryRuleRepository salaryRuleRepository;
    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public List<SalaryRuleResponse> getAll() {
        return salaryRuleRepository.findAllByOrderByRoleAscIdAsc().stream()
            .map(this::toResponse)
            .toList();
    }

    @Transactional
    public SalaryRuleResponse create(SalaryRuleRequest request) {
        SalaryRule rule = new SalaryRule();
        apply(rule, request);
        return toResponse(salaryRuleRepository.save(rule));
    }

    @Transactional
    public SalaryRuleResponse update(Long id, SalaryRuleRequest request) {
        SalaryRule rule = salaryRuleRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("SalaryRule", id));
        apply(rule, request);
        return toResponse(salaryRuleRepository.save(rule));
    }

    @Transactional
    public void delete(Long id) {
        SalaryRule rule = salaryRuleRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("SalaryRule", id));
        rule.setIsActive(false);
        salaryRuleRepository.save(rule);
    }

    private void apply(SalaryRule rule, SalaryRuleRequest request) {
        rule.setRole(request.getRole());
        if (request.getUserId() != null) {
            User user = userRepository.findById(request.getUserId())
                .orElseThrow(() -> new ResourceNotFoundException("User", request.getUserId()));
            rule.setUser(user);
        } else {
            rule.setUser(null);
        }
        rule.setBaseSalary(nz(request.getBaseSalary()));
        rule.setPerStudentFee(nz(request.getPerStudentFee()));
        rule.setNewStudentBonus(nz(request.getNewStudentBonus()));
        rule.setKpiThreshold(request.getKpiThreshold());
        rule.setKpiBonus(nz(request.getKpiBonus()));
        rule.setIsActive(request.getIsActive() == null || request.getIsActive());
        rule.setEffectiveFrom(request.getEffectiveFrom());
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
            .baseSalary(r.getBaseSalary())
            .perStudentFee(r.getPerStudentFee())
            .newStudentBonus(r.getNewStudentBonus())
            .kpiThreshold(r.getKpiThreshold())
            .kpiBonus(r.getKpiBonus())
            .isActive(r.getIsActive())
            .effectiveFrom(r.getEffectiveFrom())
            .createdAt(r.getCreatedAt())
            .build();
    }

    private static BigDecimal nz(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }
}
