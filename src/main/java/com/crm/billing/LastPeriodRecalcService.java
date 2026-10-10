package com.crm.billing;

import com.crm.dto.response.GroupEndDateDtos;
import com.crm.entity.BalanceTransaction;
import com.crm.entity.BillingPeriod;
import com.crm.entity.StudentGroup;
import com.crm.entity.enums.BalanceTransactionType;
import com.crm.entity.enums.BillingPeriodStatus;
import com.crm.entity.enums.PaymentType;
import com.crm.repository.BillingPeriodRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Yozilgan oxirgi davrni qayta hisoblash (buyurtmachi qoidasi 2026-10-10): guruh tugash sanasi o'zgarganda
 * ({@link GroupEndDateService#catchUp}) va qoida oldidan to'liq narx bilan yozilgan davrlar uchun bir martalik
 * tuzatishda ({@code POST /api/admin/repair/prorate-last-periods}).
 *
 * <p>Qamrov — yozilmaning CHARGED, migratsiyadan bo'lmagan davrlari ichida: guruh tugash sanasi tushadigani
 * ({@link AccrualCalculator#isLastPeriod}) yoki avval darslar bo'yicha hisoblangani ({@code prorated_lessons}).
 * Yangi summa — {@link AccrualCalculator#periodCharge} davrning o'z narxi ({@code fee}, {@code discount_percentage})
 * bilan. Farq: oshsa — qo'shimcha {@code PERIOD_CHARGE}, kamaysa — {@code PERIOD_REFUND}; ikkalasi davr effective
 * sanasi bilan va asl charge'ga bog'langan ({@code related_tx_id}) — FIFO'da aynan o'sha majburiyatni o'zgartiradi.
 * Davr {@code amount}, {@code prorated_lessons}, {@code lesson_price} yangilanadi. Tugashdan keyin boshlangan davrga,
 * PER_LESSON yozilmaga, muzlatishda qaytarilgan va migratsiya davrlariga tegilmaydi. Takroriy chaqiruv — farq yo'q.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LastPeriodRecalcService {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private final BillingPeriodRepository periodRepository;
    private final LedgerService ledger;
    private final LessonProrationService prorationService;

    /** Yozmasdan — guruhning joriy tugash sanasi bilan. */
    public List<GroupEndDateDtos.Recalc> plan(StudentGroup sg) {
        return plan(sg, sg.getGroup() != null ? sg.getGroup().getEndDate() : null);
    }

    /** Yozmasdan — {@code groupEnd} bilan (end-date-impact preview'i). */
    public List<GroupEndDateDtos.Recalc> plan(StudentGroup sg, LocalDate groupEnd) {
        List<GroupEndDateDtos.Recalc> out = new ArrayList<>();
        if (sg.getId() == null || sg.getPaymentType() == PaymentType.PER_LESSON) {
            return out;
        }
        AccrualCalculator.Proration proration = prorationService.forEnrollment(sg);
        for (BillingPeriod p : periodRepository.findByStudentGroupIdOrderByPeriodStartAsc(sg.getId())) {
            if (p.getStatus() != BillingPeriodStatus.CHARGED || p.getMigrationRunId() != null || p.getFee() == null) {
                continue;
            }
            if (groupEnd != null && p.getPeriodStart().isAfter(groupEnd)) {
                continue;
            }
            boolean last = AccrualCalculator.isLastPeriod(groupEnd, p.getPeriodStart(), p.getPeriodEnd());
            if (!last && p.getProratedLessons() == null) {
                continue;
            }
            AccrualCalculator.DueCharge expected = AccrualCalculator.periodCharge(p.getPeriodStart(), p.getPeriodEnd(),
                p.getFee(), p.getDiscountPercentage(), groupEnd, proration);
            BigDecimal old = Money.nz(p.getAmount());
            if (Money.eq(old, expected.amount()) && Objects.equals(p.getProratedLessons(), expected.proratedLessons())) {
                continue;
            }
            out.add(new GroupEndDateDtos.Recalc(p.getId(), sg.getId(), p.getPeriodStart(), p.getPeriodEnd(),
                Money.normalize(old), Money.normalize(expected.amount()),
                Money.normalize(expected.amount().subtract(old)), p.getProratedLessons(), expected.proratedLessons(),
                expected.lessonPrice() != null ? Money.normalize(expected.lessonPrice()) : null, p.getChargeTxId()));
        }
        return out;
    }

    /**
     * Rejani yozadi. Chaqiruvchi student → yozilma qulfini olgan; snapshot'ni chaqiruvchi yangilaydi
     * (catch-up'da {@link AccrualService#accrueLocked}, tuzatishda — alohida).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<GroupEndDateDtos.Recalc> apply(StudentGroup sg) {
        LocalDate groupEnd = sg.getGroup() != null ? sg.getGroup().getEndDate() : null;
        List<GroupEndDateDtos.Recalc> plan = plan(sg, groupEnd);
        for (GroupEndDateDtos.Recalc r : plan) {
            BillingPeriod p = periodRepository.findById(r.periodId()).orElseThrow();
            if (r.diff().signum() != 0) {
                BalanceTransaction tx = ledger.post(LedgerService.Entry.builder()
                    .enrollment(sg)
                    .type(r.diff().signum() > 0 ? BalanceTransactionType.PERIOD_CHARGE : BalanceTransactionType.PERIOD_REFUND)
                    .amount(r.diff().negate())
                    .effectiveDate(p.getPeriodStart())
                    .billingPeriodId(p.getId())
                    .relatedTxId(p.getChargeTxId())
                    .note(note(r, groupEnd))
                    .build());
                if (p.getChargeTxId() == null) {
                    p.setChargeTxId(tx.getId());
                }
            }
            p.setAmount(r.newAmount());
            p.setProratedLessons(r.newLessons());
            p.setLessonPrice(r.lessonPrice());
            periodRepository.save(p);
            log.info("Oxirgi davr qayta hisoblandi sg={} davr={} {} → {} (darslar {})", sg.getId(), p.getId(),
                r.oldAmount().toPlainString(), r.newAmount().toPlainString(), r.newLessons());
        }
        return plan;
    }

    private static String note(GroupEndDateDtos.Recalc r, LocalDate groupEnd) {
        String text = "Oxirgi davr qayta hisoblandi (guruh tugashi "
            + (groupEnd != null ? groupEnd.format(FMT) : "—") + "): " + r.start().format(FMT) + "–" + r.end().format(FMT)
            + ", " + AccrualService.groupedSum(r.oldAmount()) + " → " + AccrualService.groupedSum(r.newAmount());
        return r.newLessons() != null
            ? text + " (" + r.newLessons() + " dars × " + AccrualService.groupedSum(r.lessonPrice()) + ")"
            : text;
    }
}
