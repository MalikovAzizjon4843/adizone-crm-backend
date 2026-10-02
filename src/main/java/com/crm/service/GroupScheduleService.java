package com.crm.service;

import com.crm.entity.Classroom;
import com.crm.entity.GroupScheduleDay;
import com.crm.entity.LessonException;
import com.crm.entity.Timetable;
import com.crm.repository.GroupScheduleDayRepository;
import com.crm.repository.TimetableRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Guruhning dars kunlari — YAGONA manba. Quyidagilar shu servisdan o'qiydi:
 * <ul>
 *   <li>{@code GET /api/groups/{id}/lesson-days} ({@link GroupService#getLessonDays})</li>
 *   <li>{@code POST /api/attendance/mark} dagi "bu kunda dars bormi" tekshiruvi</li>
 *   <li>belgilanmagan darslar ({@code /api/attendance/missing}, {@code /missing/my})</li>
 * </ul>
 *
 * <p><b>Qoida:</b> {@link GroupScheduleDay} — asosiy manba; guruhda birorta ham schedule day bo'lmasa
 * (eski guruhlar, V25 dan oldin yaratilgan) — {@link Timetable}. Ikkalasi BIRLASHTIRILMAYDI.
 *
 * <p><b>Nega GroupScheduleDay ustun:</b> guruh formasida tahrirlanadigan yagona joy shu; har guruh
 * saqlanishida {@link Timetable} undan qayta quriladi ({@code GroupService.syncTimetableFromScheduleDays}:
 * guruhning barcha timetable yozuvlari o'chirilib, schedule day'lardan yaratiladi). Demak Timetable
 * — hosila nusxa: undagi "ortiqcha" yozuvlar (POST /api/timetable bilan qo'lda qo'shilgan yoki
 * vaqti noto'g'ri bo'lgani uchun schedule'dan o'tmay qolgan) keyingi saqlashda yo'qoladi va
 * jadval/davomat ekranlarida ko'rinmaydi. Oldin {@code mark} ikkalasining birlashmasini qabul qilardi —
 * natijada frontend "dars yo'q" deb ko'rsatgan kunga backend davomat yozdirardi (va aksincha).
 */
@Service
@RequiredArgsConstructor
public class GroupScheduleService {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");

    private final GroupScheduleDayRepository groupScheduleDayRepository;
    private final TimetableRepository timetableRepository;
    private final com.crm.repository.HolidayRepository holidayRepository;
    private final com.crm.repository.LessonExceptionRepository lessonExceptionRepository;

    /** Bitta dars kuni: kun KATTA harfda (MONDAY…), vaqt "HH:mm" yoki null, xona yoki null. */
    public record LessonSlot(String dayOfWeek, String startTime, String endTime, Classroom room) {
    }

    /** Dars kunlari (schedule day'lar, bo'sh bo'lsa timetable) — manba tartibida. */
    @Transactional(readOnly = true)
    public List<LessonSlot> lessonSlots(Long groupId) {
        List<LessonSlot> slots = new ArrayList<>();
        for (GroupScheduleDay d : groupScheduleDayRepository.findByGroup_IdOrderByDayOfWeekAsc(groupId)) {
            String day = normalizeDay(d.getDayOfWeek());
            if (day != null) {
                slots.add(new LessonSlot(day, normalizeTime(d.getStartTime()), normalizeTime(d.getEndTime()), d.getRoom()));
            }
        }
        if (!slots.isEmpty()) {
            return slots;
        }
        for (Timetable t : timetableRepository.findByGroupId(groupId)) {
            String day = normalizeDay(t.getDayOfWeek());
            if (day != null) {
                slots.add(new LessonSlot(day,
                    t.getStartTime() != null ? t.getStartTime().format(TIME_FMT) : null,
                    t.getEndTime() != null ? t.getEndTime().format(TIME_FMT) : null,
                    t.getClassroom()));
            }
        }
        return slots;
    }

    /** Dars bo'ladigan hafta kunlari. */
    @Transactional(readOnly = true)
    public Set<DayOfWeek> lessonWeekdays(Long groupId) {
        Set<DayOfWeek> days = new LinkedHashSet<>();
        for (LessonSlot slot : lessonSlots(groupId)) {
            try {
                days.add(DayOfWeek.valueOf(slot.dayOfWeek()));
            } catch (IllegalArgumentException ignored) {
                // Noma'lum qiymat (masalan qo'lda yozilgan "DUSHANBA") — e'tiborsiz.
            }
        }
        return days;
    }

    /**
     * Shu sanada guruhda dars bormi: hafta kuni jadvali, dam olish kunlari va dars istisnolari
     * (director-dashboard §3.5): bayram — yo'q; CANCELLED/MOVED (asl kun) — yo'q;
     * EXTRA yoki MOVED (yangi kun) — bor.
     */
    @Transactional(readOnly = true)
    public boolean hasLessonOn(Long groupId, LocalDate date) {
        if (date == null) {
            return false;
        }
        if (holidayRepository.existsById(date)) {
            return false;
        }
        boolean added = lessonExceptionRepository.findByGroupIdAndMovedTo(groupId, date).stream()
            .anyMatch(e -> e.getKind() == LessonException.Kind.MOVED)
            || lessonExceptionRepository.findByGroupIdAndLessonDate(groupId, date).stream()
                .anyMatch(e -> e.getKind() == LessonException.Kind.EXTRA);
        if (added) {
            return true;
        }
        boolean removed = lessonExceptionRepository.findByGroupIdAndLessonDate(groupId, date).stream()
            .anyMatch(e -> e.getKind() == LessonException.Kind.CANCELLED || e.getKind() == LessonException.Kind.MOVED);
        return !removed && lessonWeekdays(groupId).contains(date.getDayOfWeek());
    }

    private static String normalizeDay(String day) {
        if (day == null || day.isBlank()) {
            return null;
        }
        return day.trim().toUpperCase(Locale.ROOT);
    }

    private static String normalizeTime(String time) {
        if (time == null || time.isBlank()) {
            return null;
        }
        String t = time.trim();
        return t.length() >= 5 ? t.substring(0, 5) : t;
    }
}
