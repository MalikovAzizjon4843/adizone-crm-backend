package com.crm.service;

import com.crm.entity.enums.PayrollStatus;
import com.crm.exception.ConflictException;
import com.crm.repository.PayrollRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Set;

/**
 * Tasdiqlangan/to'langan oylik (payroll-v2: APPROVED/PAID — muzlatilgan snapshot) davriga ta'sir
 * qiladigan o'zgarish (ta'til, o'rinbosar darsi) rad etiladi — aks holda snapshot va manba farq qiladi.
 */
@Component
@RequiredArgsConstructor
public class PayrollPeriodGuard {

    private static final Set<PayrollStatus> LOCKED = Set.of(PayrollStatus.APPROVED, PayrollStatus.PAID);

    private final PayrollRepository payrollRepository;

    public boolean isLocked(Long userId, YearMonth month) {
        if (userId == null) {
            return false;
        }
        return payrollRepository.findActive(userId, month.getMonthValue(), month.getYear())
            .map(p -> LOCKED.contains(p.getStatus()))
            .orElse(false);
    }

    /** {@code [from, to]} oylaridan birida oylik APPROVED/PAID bo'lsa — 409 {@code code}. */
    public void assertUnlocked(Long userId, LocalDate from, LocalDate to, String code) {
        for (YearMonth m = YearMonth.from(from); !m.isAfter(YearMonth.from(to)); m = m.plusMonths(1)) {
            if (isLocked(userId, m)) {
                throw new ConflictException(code, m.getMonthValue() + "/" + m.getYear());
            }
        }
    }
}
