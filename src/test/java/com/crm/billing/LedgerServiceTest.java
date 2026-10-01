package com.crm.billing;

import com.crm.billing.support.AbstractBillingIT;
import com.crm.entity.BalanceTransaction;
import com.crm.entity.StudentGroup;
import com.crm.entity.enums.BalanceTransactionType;
import com.crm.repository.BalanceTransactionRepository;
import com.crm.repository.StudentGroupRepository;
import com.crm.repository.StudentRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** I1, I2, I3, I8 — docs/design/billing-v2.md §1.2. */
class LedgerServiceTest extends AbstractBillingIT {

    @Autowired
    LedgerService ledger;
    @Autowired
    StudentGroupRepository sgRepo;
    @Autowired
    StudentRepository studentRepo;
    @Autowired
    BalanceTransactionRepository txRepo;

    private LedgerService.Entry entry(Long sgId, BalanceTransactionType type, long amount, LocalDate eff) {
        return LedgerService.Entry.builder()
            .enrollment(sgRepo.findById(sgId).orElseThrow())
            .type(type)
            .amount(BigDecimal.valueOf(amount))
            .effectiveDate(eff)
            .build();
    }

    @Test
    void post_balanceEqualsLedgerSum_andStudentAggregate() {
        Long course = fixtures.course(700_000);
        Long student = fixtures.student();
        Long sgA = fixtures.enrollment(student, fixtures.group(course)).save();
        Long sgB = fixtures.enrollment(student, fixtures.group(course)).save();

        inTx(() -> {
            ledger.post(entry(sgA, BalanceTransactionType.PERIOD_CHARGE, -630_000, d("15.09.2026")));
            ledger.post(entry(sgA, BalanceTransactionType.PAYMENT, 1_000_000, d("16.09.2026")));
            ledger.post(entry(sgB, BalanceTransactionType.PERIOD_CHARGE, -500_000, d("20.09.2026")));
        });

        inTx(() -> {
            StudentGroup a = sgRepo.findById(sgA).orElseThrow();
            StudentGroup b = sgRepo.findById(sgB).orElseThrow();
            assertThat(a.getBalance()).isEqualByComparingTo("370000");
            assertThat(b.getBalance()).isEqualByComparingTo("-500000");
            assertThat(txRepo.sumAmountByStudentGroupId(sgA)).isEqualByComparingTo(a.getBalance());
            assertThat(studentRepo.findById(student).orElseThrow().getBalance()).isEqualByComparingTo("-130000");
        });
    }

    @Test
    void post_rejectsWrongSignZeroFractionAndLegacy() {
        Long sg = fixtures.enrollment(fixtures.student(), fixtures.group(fixtures.course(700_000))).save();

        assertThatThrownBy(() -> inTx(() -> ledger.post(entry(sg, BalanceTransactionType.PERIOD_CHARGE, 630_000, null))))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("manfiy");
        assertThatThrownBy(() -> inTx(() -> ledger.post(entry(sg, BalanceTransactionType.PAYMENT, -1, null))))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("musbat");
        assertThatThrownBy(() -> inTx(() -> ledger.post(entry(sg, BalanceTransactionType.PAYMENT, 0, null))))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("nol");
        assertThatThrownBy(() -> inTx(() -> ledger.post(LedgerService.Entry.builder()
                .enrollment(sgRepo.findById(sg).orElseThrow())
                .type(BalanceTransactionType.PAYMENT)
                .amount(new BigDecimal("630000.50"))
                .build())))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("butun");
        assertThatThrownBy(() -> inTx(() -> ledger.post(entry(sg, BalanceTransactionType.FREEZE, 100, null))))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("legacy");

        inTx(() -> assertThat(txRepo.findByStudentGroup_IdOrderByIdAsc(sg)).isEmpty());
    }

    @Test
    void reverse_keepsOriginalEffectiveDate_andOnlyOnce() {
        Long sg = fixtures.enrollment(fixtures.student(), fixtures.group(fixtures.course(700_000))).save();
        clock.setDate(d("17.09.2026"));

        Long paymentTxId = inTx(() -> ledger.post(entry(sg, BalanceTransactionType.PAYMENT, 630_000, d("16.09.2026"))).getId());

        BalanceTransaction reversal = inTx(() -> ledger.reverse(txRepo.findById(paymentTxId).orElseThrow(), "xato"));
        assertThat(reversal.getAmount()).isEqualByComparingTo("-630000");
        assertThat(reversal.getEffectiveDate()).isEqualTo(d("16.09.2026"));
        assertThat(reversal.getRelatedTxId()).isEqualTo(paymentTxId);

        inTx(() -> assertThat(sgRepo.findById(sg).orElseThrow().getBalance()).isEqualByComparingTo("0"));

        assertThatThrownBy(() -> inTx(() -> ledger.reverse(txRepo.findById(paymentTxId).orElseThrow(), "yana")))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("allaqachon");
    }

    @Test
    void reversal_amountMustMirrorOriginal() {
        Long sg = fixtures.enrollment(fixtures.student(), fixtures.group(fixtures.course(700_000))).save();
        Long paymentTxId = inTx(() -> ledger.post(entry(sg, BalanceTransactionType.PAYMENT, 630_000, null)).getId());

        assertThatThrownBy(() -> inTx(() -> ledger.post(LedgerService.Entry.builder()
                .enrollment(sgRepo.findById(sg).orElseThrow())
                .type(BalanceTransactionType.REVERSAL)
                .amount(BigDecimal.valueOf(-600_000))
                .relatedTxId(paymentTxId)
                .build())))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("teskarisi");
    }

    /** Kesh eskirgan bo'lsa ham (masalan A11) — oldingi balans ledgerdan olinadi. */
    @Test
    void post_usesLedgerSumNotStaleCache() {
        Long sg = fixtures.enrollment(fixtures.student(), fixtures.group(fixtures.course(700_000))).save();
        inTx(() -> ledger.post(entry(sg, BalanceTransactionType.PAYMENT, 100_000, null)));
        // keshni buzamiz
        inTx(() -> {
            StudentGroup s = sgRepo.findById(sg).orElseThrow();
            s.setBalance(BigDecimal.valueOf(999_999));
            sgRepo.save(s);
        });
        BalanceTransaction t = inTx(() -> ledger.post(entry(sg, BalanceTransactionType.PAYMENT, 50_000, null)));
        assertThat(t.getBalanceAfter()).isEqualByComparingTo("150000");
    }
}
