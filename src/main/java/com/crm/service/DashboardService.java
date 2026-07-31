package com.crm.service;

import com.crm.dto.response.DashboardStatsDto;
import com.crm.entity.enums.GroupStatus;
import com.crm.entity.enums.LeadStatus;
import com.crm.entity.enums.StudentStatus;
import com.crm.repository.GroupRepository;
import com.crm.repository.LeadRepository;
import com.crm.repository.PaymentRepository;
import com.crm.repository.StudentGroupRepository;
import com.crm.repository.StudentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

@Service
@RequiredArgsConstructor
public class DashboardService {

    private final StudentRepository studentRepository;
    private final StudentGroupRepository studentGroupRepository;
    private final GroupRepository groupRepository;
    private final LeadRepository leadRepository;
    private final PaymentRepository paymentRepository;

    @Transactional(readOnly = true)
    public DashboardStatsDto getStats() {
        LocalDate now = LocalDate.now();
        LocalDate monthStart = now.withDayOfMonth(1);
        LocalDateTime from = monthStart.atStartOfDay();
        LocalDateTime to = now.atTime(LocalTime.MAX);

        DashboardStatsDto dto = new DashboardStatsDto();

        dto.setNewOrders(leadRepository.countByCreatedAtBetween(from, to));
        dto.setFirstLessonStudents(
            studentGroupRepository.countDistinctByFirstLessonDateBetween(monthStart, now));
        dto.setNewStudents(studentRepository.countByCreatedAtBetween(from, to));
        dto.setActiveStudents(studentRepository.countByStatus(StudentStatus.ACTIVE));
        dto.setLeftFromOrder(leadRepository.countByStatus(LeadStatus.REJECTED));
        dto.setLeftFromActive(
            studentGroupRepository.countDistinctLeftBetween(monthStart, now));
        dto.setNewLeftStudents(
            studentRepository.countByStatusAndUpdatedAtBetween(StudentStatus.LEFT, from, to));
        dto.setDebtors(studentRepository.countByBalanceLessThan(BigDecimal.ZERO));
        dto.setGroups(groupRepository.countByStatus(GroupStatus.ACTIVE));
        dto.setFirstPaymentStudents(paymentRepository.countFirstPaymentsThisMonth(monthStart));
        dto.setFrozen(studentRepository.countByStatus(StudentStatus.FROZEN));
        dto.setArchived(studentRepository.countByStatus(StudentStatus.ARCHIVED));

        return dto;
    }
}
