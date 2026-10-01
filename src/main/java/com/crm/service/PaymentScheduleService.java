package com.crm.service;

import com.crm.billing.AccrualService;
import com.crm.billing.BillingGate;
import com.crm.billing.BillingSnapshotService;
import com.crm.billing.BillingStatusService;
import com.crm.entity.Student;
import com.crm.entity.StudentGroup;
import com.crm.repository.StudentGroupRepository;
import com.crm.repository.StudentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

/**
 * Billing v2 dan oldingi to'lov jadvali servisidan qolgan yagona metod: yozilma qo'shilgandan
 * keyin (guruhga qo'shish, import, lid konvertatsiyasi) accrual va snapshot.
 *
 * <p>Holat, keyingi to'lov sanasi va qarz ledgerdan hosila ({@link BillingSnapshotService},
 * {@link BillingStatusService}, docs/design/billing-v2.md §4). Eski "to'lovdan sana" hisobi
 * va narx yordamchilari o'chirilgan (§10.3).
 */
@Service
@RequiredArgsConstructor
public class PaymentScheduleService {

    private final StudentRepository studentRepository;
    private final StudentGroupRepository studentGroupRepository;
    private final BillingSnapshotService snapshotService;
    private final BillingStatusService statusService;
    private final AccrualService accrualService;
    private final BillingGate gate;

    /**
     * O'quvchining faol yozilmalari uchun bugungacha accrual (§10.2: {@code paymentStartDate ≤ bugun}
     * bo'lsa darhol charge) va snapshot. Billing o'chirilgan bo'lsa — faqat snapshot, accrual'ni
     * job keyin quvib yetadi.
     */
    @Transactional
    public LocalDate recalculateForStudent(Long studentId) {
        if (studentId == null) {
            return null;
        }
        if (gate.isEnabled()) {
            LocalDate today = statusService.today();
            for (StudentGroup sg : studentGroupRepository.findActiveByStudentId(studentId)) {
                if (Boolean.TRUE.equals(sg.getIsActive())) {
                    accrualService.accrueUpTo(sg.getId(), today);
                }
            }
        }
        snapshotService.refreshStudentFully(studentId);
        return studentRepository.findById(studentId).map(Student::getNextPaymentDate).orElse(null);
    }

    @Transactional
    public LocalDate recalculateForStudent(Student student) {
        return student != null ? recalculateForStudent(student.getId()) : null;
    }
}
