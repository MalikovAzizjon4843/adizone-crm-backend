package com.crm.service;

import com.crm.billing.AccrualService;
import com.crm.billing.BillingSnapshotService;
import com.crm.billing.support.AbstractBillingIT;
import com.crm.dto.response.DebtorsListResponse;
import com.crm.entity.StudentGroup;
import com.crm.repository.StudentGroupRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** §13 #5, #26: eslatma OVERDUE kuni va har 3 kunda, summa = qarz, muzlatilganlarga yo'q. */
class PaymentReminderScheduleTest extends AbstractBillingIT {

    @Autowired
    PaymentReminderService reminders;
    @Autowired
    AccrualService accrual;
    @Autowired
    BillingSnapshotService snapshots;
    @Autowired
    StudentGroupRepository sgRepo;

    @Test
    void sendsOnOverdueDayThenEveryThirdDay() {
        Long sg = fixtures.enrollment(fixtures.student(), fixtures.group(fixtures.course(630_000)))
            .start(d("15.10.2026")).save();
        clock.setDate(d("15.10.2026"));
        accrual.accrueUpTo(sg, d("15.10.2026"));

        // R1: qarzdor bo'lgan kun — muddat kunining o'zi (15.10), keyin har 3 kunda
        for (String day : List.of("15.10.2026", "16.10.2026", "17.10.2026", "18.10.2026", "19.10.2026", "21.10.2026",
                "24.10.2026")) {
            clock.setDate(d(day));
            snapshots.refreshAllDue(d(day));
            List<DebtorsListResponse.DebtorStudent> due = reminders.dueToday(d(day));
            boolean expected = day.equals("15.10.2026") || day.equals("18.10.2026") || day.equals("21.10.2026")
                || day.equals("24.10.2026");
            assertThat(due).as(day).hasSize(expected ? 1 : 0);
            if (expected) {
                assertThat(due.get(0).getDebt()).isEqualByComparingTo("630000");
            }
        }
    }

    @Test
    void frozenStudentGetsNoReminder() {
        Long sg = fixtures.enrollment(fixtures.student(), fixtures.group(fixtures.course(630_000)))
            .start(d("15.10.2026")).save();
        clock.setDate(d("15.10.2026"));
        accrual.accrueUpTo(sg, d("15.10.2026"));
        inTx(() -> {
            StudentGroup s = sgRepo.findById(sg).orElseThrow();
            s.setFrozenFrom(d("16.10.2026"));
            s.setIsActive(false);
        });
        clock.setDate(d("19.10.2026"));
        snapshots.refreshAllDue(d("19.10.2026"));
        assertThat(reminders.dueToday(d("19.10.2026"))).isEmpty();
    }
}
