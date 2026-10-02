package com.crm.service;

import com.crm.entity.Teacher;
import com.crm.entity.User;
import com.crm.entity.enums.UserRole;
import com.crm.exception.ResourceNotFoundException;
import com.crm.repository.TeacherRepository;
import com.crm.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Mavjud TEACHER userlar uchun Teacher profillarini ommaviy tiklaydi. */
@Service
@RequiredArgsConstructor
@Slf4j
public class TeacherProfileSyncService {

    private final UserRepository userRepository;
    private final TeacherProfileTxRunner txRunner;
    private final TeacherService teacherService;
    private final TeacherRepository teacherRepository;

    /**
     * Barcha TEACHER userlarni ko'rib chiqadi.
     *
     * <p>Metod ataylab tranzaksiyasiz: har bir user {@link TeacherProfileTxRunner}
     * orqali o'z tranzaksiyasida qayta ishlanadi. PostgreSQL da bitta xato butun
     * tranzaksiyani abort qiladi va undan keyingi har bir so'rov 25P02
     * ("current transaction is aborted") bilan qaytadi — umumiy tranzaksiyada
     * bitta nosoz user butun sinxronni yiqitardi.
     *
     * @return {@code total}, {@code created}, {@code linked}, {@code updated}, {@code errors}
     */
    public Map<String, Object> syncFromUsers() {
        List<User> teacherUsers = userRepository.findByRole(UserRole.TEACHER);

        int created = 0;
        int linked = 0;
        int updated = 0;
        List<String> errors = new ArrayList<>();

        for (User user : teacherUsers) {
            try {
                switch (txRunner.ensureInNewTransaction(user.getId())) {
                    case CREATED -> created++;
                    case LINKED -> linked++;
                    case UPDATED -> updated++;
                    default -> { }
                }
            } catch (RuntimeException e) {
                log.error("Teacher profilini sinxronlash muvaffaqiyatsiz: userId={}, username={}",
                    user.getId(), user.getUsername(), e);
                errors.add("userId=" + user.getId() + " (" + user.getUsername() + "): "
                    + rootMessage(e));
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", teacherUsers.size());
        result.put("created", created);
        result.put("linked", linked);
        result.put("updated", updated);
        result.put("errors", errors);
        log.info("Teacher sinxroni tugadi: total={}, created={}, linked={}, updated={}, errors={}",
            teacherUsers.size(), created, linked, updated, errors.size());
        return result;
    }

    /**
     * {@code POST /api/admin/repair/link-teacher-users} — o'qituvchi ↔ user bog'lanishini to'liq tiklaydi
     * (payroll-v2 §12, BUG 3). Avval faqat 1-qadam bor edi: TEACHER userda Teacher qatori UMUMAN
     * bo'lmasa u hech narsa qilmas va {@code remainingUnlinked = 0} ("bog'lanmagan profil yo'q") qaytarardi —
     * oylik esa o'sha userlarni "profil bog'lanmagan" deb o'tkazib yuborardi.
     * <ol>
     *   <li>egasiz ({@code user_id IS NULL}) profillar — ism/telefon/email bo'yicha TEACHER userga bog'lanadi;</li>
     *   <li>profili yo'q TEACHER userlar — {@link TeacherService#ensureTeacherProfile} (sync-from-users bilan
     *       bir xil qoida: telefon bo'yicha egasiz profil bo'lsa bog'lanadi, aks holda yaratiladi), har user
     *       o'z tranzaksiyasida.</li>
     * </ol>
     * Ta'rif oylik hisobi bilan bir xil: profil = {@code teachers.user_id = user.id}.
     *
     * @return {@code linkedCount, createdCount, skippedCount, remainingUnlinked} (egasiz profillar),
     *         {@code usersWithoutProfile} (TEACHER userlar), {@code items[]} — har qator: {@code type}
     *         (TEACHER|USER), {@code teacherId}, {@code userId}, {@code username}, {@code action}
     *         (LINKED|CREATED|SKIPPED), {@code reason}
     */
    public Map<String, Object> repairTeacherLinks() {
        int linked = 0;
        int created = 0;
        int skipped = 0;
        List<Map<String, Object>> items = new ArrayList<>();

        for (TeacherService.OrphanLinkResult r : teacherService.linkOrphanTeachers()) {
            if (r.linked()) {
                linked++;
            } else {
                skipped++;
            }
            items.add(item("TEACHER", r.teacherId(), r.userId(), r.username(),
                r.linked() ? "LINKED" : "SKIPPED", r.reason()));
        }

        for (User user : userRepository.findByRole(UserRole.TEACHER)) {
            if (teacherRepository.findByUser_Id(user.getId()).isPresent()) {
                continue;
            }
            Map<String, Object> it = ensureOne(user);
            switch ((String) it.get("action")) {
                case "CREATED" -> created++;
                case "LINKED" -> linked++;
                default -> skipped++;
            }
            items.add(it);
        }

        long usersWithoutProfile = userRepository.findByRole(UserRole.TEACHER).stream()
            .filter(u -> teacherRepository.findByUser_Id(u.getId()).isEmpty()).count();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("linkedCount", linked);
        result.put("createdCount", created);
        result.put("skippedCount", skipped);
        result.put("remainingUnlinked", teacherService.countTeachersWithoutUser());
        result.put("usersWithoutProfile", usersWithoutProfile);
        result.put("items", items);
        log.info("Teacher bog'lanishi tiklandi: linked={}, created={}, skipped={}, usersWithoutProfile={}",
            linked, created, skipped, usersWithoutProfile);
        return result;
    }

    /**
     * {@code POST /api/teachers/{userId}/ensure-profile} — bitta user uchun repair 2-qadami (UI tugmasi).
     * Javob repair bilan bir xil shaklda: {@code linkedCount, createdCount, skippedCount, items[1]};
     * {@code action}: CREATED | LINKED | EXISTS (profil allaqachon bor) | SKIPPED (sabab bilan).
     */
    public Map<String, Object> ensureProfile(Long userId) {
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new ResourceNotFoundException("User", userId));
        Map<String, Object> it = teacherRepository.findByUser_Id(user.getId())
            .map(t -> item("USER", t.getId(), user.getId(), user.getUsername(), "EXISTS", null))
            .orElseGet(() -> ensureOne(user));
        String action = (String) it.get("action");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("linkedCount", "LINKED".equals(action) ? 1 : 0);
        result.put("createdCount", "CREATED".equals(action) ? 1 : 0);
        result.put("skippedCount", "SKIPPED".equals(action) ? 1 : 0);
        result.put("items", List.of(it));
        return result;
    }

    /**
     * Profili yo'q bitta user: rol TEACHER emas yoki shu telefon/email li profil BOSHQA userga bog'langan
     * (va egasiz mos profil yo'q) bo'lsa — SKIPPED (dublikat profil yaratilmaydi, qo'lda tekshiriladi);
     * aks holda {@link TeacherService#ensureTeacherProfile} o'z tranzaksiyasida (CREATED / LINKED).
     */
    private Map<String, Object> ensureOne(User user) {
        if (user.getRole() != UserRole.TEACHER) {
            return item("USER", null, user.getId(), user.getUsername(), "SKIPPED",
                "Roli TEACHER emas: " + user.getRole());
        }
        List<Teacher> candidates = new ArrayList<>();
        String phone = trimToNull(user.getPhone());
        String email = trimToNull(user.getEmail());
        if (phone != null) {
            candidates.addAll(teacherRepository.findAllByPhone(phone));
        }
        if (email != null) {
            candidates.addAll(teacherRepository.findAllByEmailIgnoreCase(email));
        }
        boolean orphanByPhone = phone != null && candidates.stream()
            .anyMatch(t -> t.getUser() == null && phone.equals(t.getPhone()));
        Teacher taken = candidates.stream()
            .filter(t -> t.getUser() != null && !t.getUser().getId().equals(user.getId()))
            .findFirst().orElse(null);
        if (!orphanByPhone && taken != null) {
            return item("USER", taken.getId(), user.getId(), user.getUsername(), "SKIPPED",
                "Shu telefon/email li profil #" + taken.getId() + " boshqa userga (#" + taken.getUser().getId()
                    + ") bog'langan — qo'lda tekshiring");
        }
        try {
            TeacherService.ProfileOutcome outcome = txRunner.ensureInNewTransaction(user.getId());
            Long teacherId = teacherRepository.findByUser_Id(user.getId()).map(Teacher::getId).orElse(null);
            return switch (outcome) {
                case CREATED -> item("USER", teacherId, user.getId(), user.getUsername(), "CREATED", null);
                case LINKED -> item("USER", teacherId, user.getId(), user.getUsername(), "LINKED",
                    "Telefon bo'yicha egasiz profil topildi");
                default -> item("USER", teacherId, user.getId(), user.getUsername(), "SKIPPED",
                    "Profil yaratilmadi: " + outcome);
            };
        } catch (RuntimeException e) {
            log.error("Teacher profilini tiklash muvaffaqiyatsiz: userId={}", user.getId(), e);
            return item("USER", null, user.getId(), user.getUsername(), "SKIPPED", rootMessage(e));
        }
    }

    private static String trimToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static Map<String, Object> item(String type, Long teacherId, Long userId, String username,
                                            String action, String reason) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        m.put("teacherId", teacherId);
        m.put("userId", userId);
        m.put("username", username);
        m.put("action", action);
        m.put("reason", reason);
        return m;
    }

    private static String rootMessage(Throwable e) {
        Throwable cur = e;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        String message = cur.getMessage();
        return message != null ? message : cur.getClass().getSimpleName();
    }
}
