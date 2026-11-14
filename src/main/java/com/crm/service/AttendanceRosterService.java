package com.crm.service;

import com.crm.entity.Attendance;
import com.crm.entity.Student;
import com.crm.entity.StudentGroup;
import com.crm.repository.AttendanceRepository;
import com.crm.repository.StudentGroupRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Kunlik davomat ro'yxati — YAGONA manba: CRM {@code GET /api/attendance/group/{id}?date=} va Mini App o'qituvchi
 * rejimi ({@code GET/PUT /api/app/teacher/attendance/{groupId}}, bugungi darslardagi "belgilangan / jami").
 *
 * <p>Ro'yxat = shu kuni faol yozilmalar ({@link AttendanceDueService#isActiveOn}: qo'shilgan, chiqmagan,
 * muzlatilmagan; sinov ham) ∪ shu kungi saqlangan yozuvlar (keyin chiqib ketganlar ham ko'rinadi). Yozuvi borlarda —
 * yozuv, qolganlarda {@code attendance = null} ("hali belgilanmagan"). Ism bo'yicha saralangan.
 *
 * <p>Ilgari CRM kunda bitta yozuv bo'lsa ham FAQAT yozuvlarni qaytarardi — qisman saqlangan kunda belgilanmagan
 * o'quvchi ro'yxatdan butunlay yo'qolardi (prod: guruh 27, 14.11.2026).
 */
@Service
@RequiredArgsConstructor
public class AttendanceRosterService {

    private final StudentGroupRepository studentGroupRepository;
    private final AttendanceRepository attendanceRepository;

    /** Ro'yxat qatori: {@code attendance} — shu kungi yozuv yoki {@code null} (belgilanmagan). */
    public record Entry(Long studentId, String fullName, Attendance attendance) {

        public boolean marked() {
            return attendance != null;
        }
    }

    @Transactional(readOnly = true)
    public List<Entry> roster(Long groupId, LocalDate day) {
        Map<Long, Student> students = new LinkedHashMap<>();
        for (StudentGroup sg : studentGroupRepository.findByGroupId(groupId)) {
            if (sg.getStudent() != null && AttendanceDueService.isActiveOn(sg, day)) {
                students.putIfAbsent(sg.getStudent().getId(), sg.getStudent());
            }
        }
        Map<Long, Attendance> marked = new HashMap<>();
        for (Attendance a : attendanceRepository.findByGroup_IdAndAttendanceDate(groupId, day)) {
            marked.put(a.getStudent().getId(), a);
            students.putIfAbsent(a.getStudent().getId(), a.getStudent());
        }
        return students.values().stream()
            .map(s -> new Entry(s.getId(), fullName(s), marked.get(s.getId())))
            .sorted(Comparator.comparing(Entry::fullName).thenComparing(Entry::studentId))
            .toList();
    }

    /** "Ism Familiya" (bo'sh qismsiz). */
    public static String fullName(Student s) {
        String first = s.getFirstName() != null ? s.getFirstName().trim() : "";
        String last = s.getLastName() != null ? s.getLastName().trim() : "";
        return (first + " " + last).trim();
    }
}
