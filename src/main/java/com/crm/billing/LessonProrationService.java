package com.crm.billing;

import com.crm.entity.Group;
import com.crm.entity.Holiday;
import com.crm.entity.LessonException;
import com.crm.entity.StudentGroup;
import com.crm.repository.HolidayRepository;
import com.crm.repository.LessonExceptionRepository;
import com.crm.repository.SettingRepository;
import com.crm.service.GroupScheduleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Oxirgi davr darslar bo'yicha (buyurtmachi qoidasi 2026-10-10) — {@link AccrualCalculator.Proration} manbai.
 *
 * <ul>
 *   <li>oyiga darslar soni — sozlama {@code billing.lessons_per_month} (yo'q yoki musbat butun son emas — 12);</li>
 *   <li>darslar — guruh jadvali ({@link GroupScheduleService#lessonWeekdays}) bo'yicha, bayramlar va dars istisnolari
 *       bilan ({@link GroupScheduleService#isLessonDay}: CANCELLED / MOVED asl kuni — yo'q, EXTRA / MOVED yangi kuni —
 *       bor). Jadvalsiz guruh — null (oxirgi davr to'liq {@code c}) va log ogohlantirish.</li>
 * </ul>
 * Hamma narsa dangasa o'qiladi: davr oxirgi bo'lmasa bazaga murojaat yo'q.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LessonProrationService {

    public static final String LESSONS_PER_MONTH_KEY = "billing.lessons_per_month";
    public static final int DEFAULT_LESSONS_PER_MONTH = 12;

    private final SettingRepository settingRepository;
    private final GroupScheduleService groupScheduleService;
    private final HolidayRepository holidayRepository;
    private final LessonExceptionRepository lessonExceptionRepository;

    /** {@code billing.lessons_per_month}; yo'q yoki noto'g'ri — 12. */
    public int lessonsPerMonth() {
        String raw = settingRepository.findBySettingKey(LESSONS_PER_MONTH_KEY)
            .map(s -> s.getSettingValue() != null ? s.getSettingValue().trim() : null)
            .orElse(null);
        if (raw == null || raw.isEmpty()) {
            return DEFAULT_LESSONS_PER_MONTH;
        }
        try {
            int value = Integer.parseInt(raw);
            if (value > 0) {
                return value;
            }
        } catch (NumberFormatException ignored) {
            // pastda ogohlantirish
        }
        log.warn("Sozlama {} = '{}' musbat butun son emas — {} olinadi", LESSONS_PER_MONTH_KEY, raw,
            DEFAULT_LESSONS_PER_MONTH);
        return DEFAULT_LESSONS_PER_MONTH;
    }

    public AccrualCalculator.Proration forEnrollment(StudentGroup sg) {
        return sg != null ? forGroup(sg.getGroup()) : AccrualCalculator.Proration.NONE;
    }

    public AccrualCalculator.Proration forGroup(Group group) {
        return group != null && group.getId() != null
            ? new GroupProration(group.getId()) : AccrualCalculator.Proration.NONE;
    }

    private final class GroupProration implements AccrualCalculator.Proration {

        private final Long groupId;
        private Integer lessonsPerMonth;
        private Set<DayOfWeek> weekdays;
        private List<LessonException> exceptions;
        private boolean warned;

        private GroupProration(Long groupId) {
            this.groupId = groupId;
        }

        @Override
        public int lessonsPerMonth() {
            if (lessonsPerMonth == null) {
                lessonsPerMonth = LessonProrationService.this.lessonsPerMonth();
            }
            return lessonsPerMonth;
        }

        @Override
        public Integer lessons(LocalDate from, LocalDate to) {
            if (weekdays == null) {
                weekdays = groupScheduleService.lessonWeekdays(groupId);
            }
            if (weekdays.isEmpty()) {
                if (!warned) {
                    log.warn("Guruh {} jadvalsiz — oxirgi davr darslar bo'yicha emas, to'liq oylik bilan", groupId);
                    warned = true;
                }
                return null;
            }
            if (from == null || to == null || to.isBefore(from)) {
                return 0;
            }
            if (exceptions == null) {
                exceptions = lessonExceptionRepository.findByGroupIdOrderByLessonDateDesc(groupId);
            }
            Set<LocalDate> holidays = holidayRepository.findByHolidayDateBetweenOrderByHolidayDateAsc(from, to).stream()
                .map(Holiday::getHolidayDate).collect(Collectors.toSet());
            int count = 0;
            for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
                if (GroupScheduleService.isLessonDay(d, weekdays, holidays.contains(d), exceptions)) {
                    count++;
                }
            }
            return count;
        }
    }
}
