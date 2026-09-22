package com.crm.service;

import com.crm.dto.request.TeacherRequest;
import com.crm.dto.response.TeacherResponse;
import com.crm.entity.Teacher;
import com.crm.entity.User;
import com.crm.entity.enums.UserRole;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Xodim (hozircha o'qituvchi) statusini o'zgartirishning yagona kirish nuqtasi.
 *
 * <p>Nega alohida servis: Teacher statusi bog'langan userga ham ta'sir qiladi,
 * user esa {@link UserService#setActive} orqali bloklanadi. {@code UserService}
 * allaqachon {@code TeacherService} ga bog'liq — {@code TeacherService} ga
 * {@code UserService} ni inject qilish aylanma bog'liqlik bo'lardi. Grafik:
 * <pre>
 *   StaffStatusService ──► UserService ──► TeacherService
 *            └──────────────────────────────────┘
 * </pre>
 *
 * <p>Rekursiya yo'q: {@code setActive -> syncTeacherProfile} faqat Teacher'ni
 * yozadi va bu yerga qaytib kelmaydi; ikkala tomon ham holat haqiqatan
 * o'zgargandagina yozadi.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class StaffStatusService {

    private final TeacherService teacherService;
    private final UserService userService;

    /**
     * PUT /api/teachers/{id}: oddiy maydonlar TeacherService da, status shu yerda —
     * bitta tranzaksiyada, ya'ni user bloklanib, profil saqlanmay qolmaydi.
     */
    @Transactional
    public TeacherResponse updateTeacher(Long teacherId, TeacherRequest request) {
        teacherService.updateTeacher(teacherId, request);
        if (request.getStatus() != null) {
            changeTeacherStatus(teacherId, request.getStatus());
        }
        return teacherService.getTeacherById(teacherId);
    }

    /** DELETE /api/teachers/{id}: yozuv o'chirilmaydi, faqat INACTIVE. */
    @Transactional
    public void deactivateTeacher(Long teacherId) {
        changeTeacherStatus(teacherId, Teacher.STATUS_INACTIVE);
    }

    /**
     * Teacher -> User yo'nalishi:
     * <ul>
     *   <li>INACTIVE ga o'tsa — user bloklanadi (sessiyalari ham yopiladi);</li>
     *   <li>INACTIVE dan chiqsa — user blokdan chiqariladi;</li>
     *   <li>ACTIVE &harr; ON_LEAVE — userga tegilmaydi, u faolligicha qoladi;</li>
     *   <li>bog'lanmagan yoki roli TEACHER bo'lmagan user — faqat Teacher yangilanadi.</li>
     * </ul>
     */
    @Transactional
    public void changeTeacherStatus(Long teacherId, String requestedStatus) {
        Teacher teacher = teacherService.findById(teacherId);
        String status = Teacher.normalizeStatus(requestedStatus);
        boolean wantActive = !Teacher.STATUS_INACTIVE.equals(status);

        User user = teacher.getUser();
        if (user != null && user.getRole() == UserRole.TEACHER
                && wantActive != isActive(user)) {
            // setActive o'zi syncTeacherProfile ni chaqiradi va Teacher'ni
            // ACTIVE/INACTIVE ga o'tkazadi.
            userService.setActive(user.getId(), wantActive);
            log.info("O'qituvchi statusi userga uzatildi: teacherId={}, userId={}, status={}",
                teacherId, user.getId(), status);
        }

        // Aniq statusni qo'yamiz: sync faqat ACTIVE/INACTIVE ni biladi, ON_LEAVE ni emas.
        // Sync allaqachon to'g'ri holatga keltirgan bo'lsa, bu chaqiruv hech narsa yozmaydi.
        teacherService.changeStatus(teacherId, status);
    }

    private static boolean isActive(User user) {
        return !Boolean.FALSE.equals(user.getIsActive());
    }
}
