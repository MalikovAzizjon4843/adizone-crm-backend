package com.crm.service;

import com.crm.repository.HolidayRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.HashSet;
import java.util.Set;

/**
 * Ish kunlari — buyurtmachi qarori (leaves-exams-contracts §0, §3.1): dushanba–shanba, bayramlar
 * ({@code holidays}) ish kuni EMAS. Haqsiz ta'til ulushi va ta'til javobidagi {@code workdays} shu yerdan.
 */
@Component
@RequiredArgsConstructor
public class WorkdayCalendar {

    private final HolidayRepository holidayRepository;

    /** {@code [from, to]} (ikkalasi kiritilgan) dagi Du–Sha, bayram bo'lmagan kunlar soni. */
    public int workdays(LocalDate from, LocalDate to) {
        if (from == null || to == null || to.isBefore(from)) {
            return 0;
        }
        return count(from, to, holidays(from, to));
    }

    public int workdays(YearMonth month) {
        return workdays(month.atDay(1), month.atEndOfMonth());
    }

    public Set<LocalDate> holidays(LocalDate from, LocalDate to) {
        Set<LocalDate> out = new HashSet<>();
        holidayRepository.findByHolidayDateBetweenOrderByHolidayDateAsc(from, to)
            .forEach(h -> out.add(h.getHolidayDate()));
        return out;
    }

    /** Oldindan yuklangan bayramlar bilan (payroll da bir oy uchun bir marta). */
    public static int count(LocalDate from, LocalDate to, Set<LocalDate> holidays) {
        int n = 0;
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            if (isWorkday(d, holidays)) {
                n++;
            }
        }
        return n;
    }

    public static boolean isWorkday(LocalDate d, Set<LocalDate> holidays) {
        return d.getDayOfWeek() != DayOfWeek.SUNDAY && !holidays.contains(d);
    }
}
