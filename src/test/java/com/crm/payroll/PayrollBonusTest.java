package com.crm.payroll;

import com.crm.dto.response.PayrollCalculationDetails;
import com.crm.dto.response.PayrollResponse;
import com.crm.entity.enums.BonusPenaltyKind;
import com.crm.entity.enums.BonusPenaltyStatus;
import com.crm.entity.enums.UserRole;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Payroll v2 — qaror 3: bonus/jarima DRAFT da "kutilmoqda", APPROVE da APPLIED, cancel da PENDING. */
class PayrollBonusTest extends PayrollItBase {

    private Staff teacher() {
        Staff t = teacherStaff();
        teacherRule(t, 3_000_000, 100_000);
        pay(monthly(t.teacherId(), 700_000, "15.09.2026"), 700_000, "15.09.2026");
        return t;
    }

    private PayrollCalculationDetails details(Long payrollId) {
        return calculator.fromJson(payroll(payrollId).getCalculationDetails());
    }

    @Test
    void draft_showsPendingBonusesAsPreview_deleteAndRecalculateLoseNothing() {
        Staff t = teacher();
        Long bonus = teacherBonus(t.teacherId(), BonusPenaltyKind.BONUS, 200_000, "10.09.2026");
        Long penalty = teacherBonus(t.teacherId(), BonusPenaltyKind.PENALTY, 50_000, "12.09.2026");
        Long october = teacherBonus(t.teacherId(), BonusPenaltyKind.BONUS, 999_000, "05.10.2026");

        PayrollResponse draft = draftFor(t.userId(), SEP);
        assertThat(draft.getBonusPenaltyAdjustment()).isEqualByComparingTo("150000");
        assertThat(draft.getNetSalary()).isEqualByComparingTo("3250000");
        PayrollCalculationDetails d = details(draft.getId());
        assertThat(d.lines()).filteredOn(l -> l.code().equals(PayrollCalculationDetails.BONUS))
            .singleElement().satisfies(l -> assertThat(l.status()).isEqualTo("PENDING"));
        assertThat(d.items().bonuses()).extracting(PayrollCalculationDetails.Bonus::id)
            .containsExactly(bonus, penalty);
        for (Long id : new Long[]{bonus, penalty, october}) {
            assertThat(bonus(id).getStatus()).isEqualTo(BonusPenaltyStatus.PENDING);
            assertThat(bonus(id).getAppliedToPayrollId()).isNull();
        }

        payroll.recalculate(draft.getId());
        assertThat(bonus(bonus).getStatus()).isEqualTo(BonusPenaltyStatus.PENDING);
        assertThat(payroll(draft.getId()).getNetSalary()).isEqualByComparingTo("3250000");

        payroll.deletePayroll(draft.getId());
        assertThat(bonus(bonus).getStatus()).isEqualTo(BonusPenaltyStatus.PENDING);
        assertThat(bonus(penalty).getStatus()).isEqualTo(BonusPenaltyStatus.PENDING);
        assertThat(draftFor(t.userId(), SEP).getNetSalary()).isEqualByComparingTo("3250000");
    }

    @Test
    void approve_appliesBonuses_cancelOfPaidReturnsThemToPending() {
        Staff t = teacher();
        Long bonus = teacherBonus(t.teacherId(), BonusPenaltyKind.BONUS, 200_000, "10.09.2026");
        Long id = draftFor(t.userId(), SEP).getId();

        // DRAFT dan keyin kiritilgan jarima ham APPROVE paytida qulf ostida o'qiladi
        Long late = teacherBonus(t.teacherId(), BonusPenaltyKind.PENALTY, 30_000, "29.09.2026");
        PayrollResponse approved = payroll.approve(id, null);
        assertThat(approved.getNetSalary()).isEqualByComparingTo("3270000");
        assertThat(bonus(bonus).getStatus()).isEqualTo(BonusPenaltyStatus.APPLIED);
        assertThat(bonus(bonus).getAppliedToPayrollId()).isEqualTo(id);
        assertThat(bonus(late).getAppliedToPayrollId()).isEqualTo(id);
        assertThat(details(id).lines()).filteredOn(l -> l.code().equals(PayrollCalculationDetails.BONUS))
            .singleElement().satisfies(l -> assertThat(l.status()).isEqualTo("APPLIED"));

        payroll.markAsPaid(id, null, null);
        fixtures.loginAs(UserRole.SUPER_ADMIN);
        payroll.cancel(id, "Qayta hisoblash kerak");
        assertThat(bonus(bonus).getStatus()).isEqualTo(BonusPenaltyStatus.PENDING);
        assertThat(bonus(bonus).getAppliedToPayrollId()).isNull();
        assertThat(bonus(late).getStatus()).isEqualTo(BonusPenaltyStatus.PENDING);

        // Keyingi DRAFT ularni yana oladi
        assertThat(draftFor(t.userId(), SEP).getBonusPenaltyAdjustment()).isEqualByComparingTo("170000");
    }

    @Test
    void appliedBonus_isTakenOnce_otherDraftDoesNotApplyItAgain() {
        Staff t = teacher();
        Long bonus = teacherBonus(t.teacherId(), BonusPenaltyKind.BONUS, 200_000, "10.09.2026");
        Long sep = draftFor(t.userId(), SEP).getId();
        Long oct = draftFor(t.userId(), OCT).getId();
        assertThat(payroll(oct).getBonusPenaltyAdjustment()).isEqualByComparingTo("200000"); // ko'rinadi

        payroll.approve(sep, null);
        PayrollResponse octApproved = payroll.approve(oct, null);
        assertThat(octApproved.getBonusPenaltyAdjustment()).isEqualByComparingTo("0");
        assertThat(bonus(bonus).getAppliedToPayrollId()).isEqualTo(sep);
    }

    @Test
    void penaltiesAboveSalary_cannotBeApproved() {
        Staff t = teacher();
        teacherBonus(t.teacherId(), BonusPenaltyKind.PENALTY, 5_000_000, "10.09.2026");
        Long id = draftFor(t.userId(), SEP).getId();
        assertCode(() -> payroll.approve(id, null), "payroll.netNegative");
        assertThat(payroll(id).getStatus()).isEqualTo(com.crm.entity.enums.PayrollStatus.DRAFT);
    }
}
