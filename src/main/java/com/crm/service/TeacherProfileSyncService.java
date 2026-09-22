package com.crm.service;

import com.crm.entity.User;
import com.crm.entity.enums.UserRole;
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

    private static String rootMessage(Throwable e) {
        Throwable cur = e;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        String message = cur.getMessage();
        return message != null ? message : cur.getClass().getSimpleName();
    }
}
