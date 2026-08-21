package com.crm.service;

import com.crm.config.Messages;
import com.crm.dto.request.CreateUserRequest;
import com.crm.dto.response.PasswordResetResponse;
import com.crm.dto.response.UserResponse;
import com.crm.dto.response.UsernamePreviewResponse;
import com.crm.entity.Teacher;
import com.crm.entity.User;
import com.crm.entity.enums.UserRole;
import com.crm.exception.BadRequestException;
import com.crm.exception.ForbiddenException;
import com.crm.exception.ResourceNotFoundException;
import com.crm.repository.RefreshTokenRepository;
import com.crm.repository.TeacherRepository;
import com.crm.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class UserService {

    /** Chalkash belgilarsiz alifbo: 0/O va 1/l/I ishlatilmaydi. */
    private static final String PASSWORD_ALPHABET =
        "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";
    private static final int TEMP_PASSWORD_LENGTH = 10;
    private static final int MAX_USERNAME_ATTEMPTS = 1000;

    private static final SecureRandom RANDOM = new SecureRandom();

    /** Kirill -> lotin. Ъ va Ь bo'sh qatorga o'tadi (tushib qoladi). */
    private static final Map<Character, String> CYRILLIC = buildCyrillicMap();

    private static Map<Character, String> buildCyrillicMap() {
        Map<Character, String> m = new HashMap<>();
        String[][] pairs = {
            {"А", "A"}, {"Б", "B"}, {"В", "V"}, {"Г", "G"}, {"Д", "D"}, {"Е", "E"},
            {"Ё", "Yo"}, {"Ж", "J"}, {"З", "Z"}, {"И", "I"}, {"Й", "Y"}, {"К", "K"},
            {"Л", "L"}, {"М", "M"}, {"Н", "N"}, {"О", "O"}, {"П", "P"}, {"Р", "R"},
            {"С", "S"}, {"Т", "T"}, {"У", "U"}, {"Ф", "F"}, {"Х", "X"}, {"Ц", "Ts"},
            {"Ч", "Ch"}, {"Ш", "Sh"}, {"Щ", "Sh"}, {"Ъ", ""}, {"Ы", "I"}, {"Ь", ""},
            {"Э", "E"}, {"Ю", "Yu"}, {"Я", "Ya"},
            // o'zbek kirillining qo'shimcha harflari
            {"Ў", "O"}, {"Қ", "Q"}, {"Ғ", "G"}, {"Ҳ", "H"}
        };
        for (String[] p : pairs) {
            char upper = p[0].charAt(0);
            m.put(upper, p[1]);
            char lower = Character.toLowerCase(upper);
            m.put(lower, p[1].toLowerCase(Locale.ROOT));
        }
        return m;
    }

    private final UserRepository userRepository;
    private final Messages messages;
    private final TeacherRepository teacherRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;

    // ------------------------------------------------------------------
    // Foydalanuvchi yaratish
    // ------------------------------------------------------------------

    @Transactional
    public UserResponse createUser(CreateUserRequest request) {
        String username = resolveUsername(request);
        validateEmailAndPhone(request.getEmail(), request.getPhone(), null);

        User user = User.builder()
            .firstName(request.getFirstName())
            .lastName(request.getLastName())
            .username(username)
            .password(passwordEncoder.encode(request.getPassword()))
            .role(request.getRole() != null ? request.getRole() : UserRole.TEACHER)
            .phone(normalizeBlank(request.getPhone()))
            .email(normalizeBlank(request.getEmail()))
            .isActive(request.getIsActive() != null ? request.getIsActive() : true)
            .build();

        User saved = userRepository.save(user);
        log.info("User created: id={}, username={}, role={}, by={}",
            saved.getId(), saved.getUsername(), saved.getRole(), currentUsername());
        return toResponse(saved);
    }

    @Transactional
    public UserResponse createForTeacher(Long teacherId, CreateUserRequest request) {
        Teacher teacher = teacherRepository.findById(teacherId)
            .orElseThrow(() -> new ResourceNotFoundException(
                messages.get("error.teacher.notFound", teacherId)));

        if (request.getUsername() != null && !request.getUsername().isBlank()) {
            Optional<User> existing = userRepository.findByUsername(request.getUsername());
            if (existing.isPresent()) {
                linkTeacherToUser(teacher, existing.get());
                return toResponse(existing.get());
            }
        }

        String firstName = request.getFirstName() != null
            ? request.getFirstName() : teacher.getFirstName();
        String lastName = request.getLastName() != null
            ? request.getLastName() : teacher.getLastName();

        String username = request.getUsername() != null && !request.getUsername().isBlank()
            ? request.getUsername()
            : generateUsername(firstName, lastName);

        User user = User.builder()
            .firstName(firstName)
            .lastName(lastName)
            .username(username)
            .password(passwordEncoder.encode(request.getPassword()))
            .role(UserRole.TEACHER)
            .phone(request.getPhone() != null ? request.getPhone() : teacher.getPhone())
            .email(request.getEmail() != null ? request.getEmail() : teacher.getEmail())
            .isActive(true)
            .build();

        User saved = userRepository.save(user);
        linkTeacherToUser(teacher, saved);
        return toResponse(saved);
    }

    private String resolveUsername(CreateUserRequest request) {
        if (request.getUsername() != null && !request.getUsername().isBlank()) {
            return request.getUsername().trim();
        }
        return generateUsername(request.getFirstName(), request.getLastName());
    }

    // ------------------------------------------------------------------
    // Login (username) generatsiyasi
    // ------------------------------------------------------------------

    /**
     * Ism-familiyadan bo'sh login yasaydi: "Nigina Yunusova" -> "N.Yunusova".
     * Band bo'lsa oxiriga raqam qo'shiladi: N.Yunusova2, N.Yunusova3 ...
     * Tekshiruv registrga bog'liq emas.
     */
    public String generateUsername(String firstName, String lastName) {
        String base = buildUsernameBase(firstName, lastName);
        if (!userRepository.existsByUsernameIgnoreCase(base)) {
            return base;
        }
        for (int i = 2; i < MAX_USERNAME_ATTEMPTS; i++) {
            String candidate = base + i;
            if (!userRepository.existsByUsernameIgnoreCase(candidate)) {
                return candidate;
            }
        }
        throw new BadRequestException(messages.get("user.username.generateFailed"));
    }

    /** Taklif ko'rinishi: yaratmasdan, faqat ko'rsatish uchun. */
    public UsernamePreviewResponse previewUsername(String firstName, String lastName) {
        String base = buildUsernameBase(firstName, lastName);
        boolean available = !userRepository.existsByUsernameIgnoreCase(base);
        return UsernamePreviewResponse.builder()
            .username(available ? base : generateUsername(firstName, lastName))
            .available(available)
            .build();
    }

    /**
     * {Ism birinchi harfi}.{Familiya}. O'zbek harflari lotinga o'tkaziladi,
     * probel/defis/apostrof olib tashlanadi, [A-Za-z0-9.] dan tashqarisi tushiriladi.
     * Familiya bo'sh bo'lsa — to'liq ism ishlatiladi.
     */
    private String buildUsernameBase(String firstName, String lastName) {
        String first = normalizeNamePart(firstName);
        String last = normalizeNamePart(lastName);

        if (last.isEmpty() && first.isEmpty()) {
            throw new BadRequestException(messages.get("user.username.nameRequired"));
        }
        if (last.isEmpty()) {
            return capitalize(first);
        }
        if (first.isEmpty()) {
            return capitalize(last);
        }
        return Character.toUpperCase(first.charAt(0)) + "." + capitalize(last);
    }

    private static String normalizeNamePart(String raw) {
        if (raw == null) {
            return "";
        }
        return transliterate(raw.trim())
            // o'zbek apostroflari: o'->o, g'->g, ʻ va ʼ olib tashlanadi
            .replace('ʻ', '\'')
            .replace('ʼ', '\'')
            .replace('‘', '\'')
            .replace('’', '\'')
            .replace("'", "")
            .replace("-", "")
            .replaceAll("\\s+", "")
            .replaceAll("[^A-Za-z0-9.]", "");
    }

    /**
     * Kirill harflarni lotinga o'giradi: "Нигина Юнусова" -> "Nigina Yunusova".
     * Ko'p harfli almashtirishlar (Ю->Yu) qo'shni harf ham katta bo'lsa
     * to'liq katta yoziladi, ya'ni "ЮНУСОВА" -> "YUNUSOVA".
     */
    private static String transliterate(String raw) {
        StringBuilder sb = new StringBuilder(raw.length() + 8);
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            String mapped = CYRILLIC.get(c);
            if (mapped == null) {
                sb.append(c);
                continue;
            }
            if (mapped.length() > 1 && Character.isUpperCase(c) && nextIsUpper(raw, i)) {
                sb.append(mapped.toUpperCase(Locale.ROOT));
            } else {
                sb.append(mapped);
            }
        }
        return sb.toString();
    }

    private static boolean nextIsUpper(String s, int i) {
        for (int j = i + 1; j < s.length(); j++) {
            char c = s.charAt(j);
            if (Character.isLetter(c)) {
                return Character.isUpperCase(c);
            }
        }
        // Oxirgi harf bo'lsa — oldingisiga qarab qaror qilamiz
        for (int j = i - 1; j >= 0; j--) {
            char c = s.charAt(j);
            if (Character.isLetter(c)) {
                return Character.isUpperCase(c);
            }
        }
        return false;
    }

    /** Faqat birinchi harf katta; qolganiga TEGILMAYDI ("MacDonald" o'zgarmaydi). */
    private static String capitalize(String s) {
        if (s.isEmpty()) {
            return s;
        }
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    // ------------------------------------------------------------------
    // Parolni tiklash
    // ------------------------------------------------------------------

    /**
     * Admin foydalanuvchiga vaqtinchalik parol beradi. Ochiq parol javobda
     * QAYTARILADI (admin xodimga og'zaki aytadi) va hech qachon logga yozilmaydi.
     */
    @Transactional
    public PasswordResetResponse resetPassword(Long userId) {
        User target = userRepository.findById(userId)
            .orElseThrow(() -> new ResourceNotFoundException(
                messages.get("error.user.notFound", userId)));
        User actor = requireCurrentUser();

        if (actor.getId().equals(target.getId())) {
            throw new BadRequestException(messages.get("user.password.resetSelf"));
        }
        if (target.getRole() == UserRole.SUPER_ADMIN && actor.getRole() != UserRole.SUPER_ADMIN) {
            throw new ForbiddenException(messages.get("user.password.resetSuperAdmin"));
        }

        String temporaryPassword = generateTemporaryPassword();
        target.setPassword(passwordEncoder.encode(temporaryPassword));
        userRepository.save(target);

        int revoked = refreshTokenRepository.revokeAllByUserId(target.getId());

        log.info("Password reset: target={} (id={}), by={} (id={}), revokedSessions={}",
            target.getUsername(), target.getId(), actor.getUsername(), actor.getId(), revoked);

        return PasswordResetResponse.builder()
            .username(target.getUsername())
            .temporaryPassword(temporaryPassword)
            .build();
    }

    private static String generateTemporaryPassword() {
        StringBuilder sb = new StringBuilder(TEMP_PASSWORD_LENGTH);
        for (int i = 0; i < TEMP_PASSWORD_LENGTH; i++) {
            sb.append(PASSWORD_ALPHABET.charAt(RANDOM.nextInt(PASSWORD_ALPHABET.length())));
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // Faollashtirish / nofaol qilish
    // ------------------------------------------------------------------

    @Transactional
    public UserResponse setActive(Long userId, boolean active) {
        User target = userRepository.findById(userId)
            .orElseThrow(() -> new ResourceNotFoundException(
                messages.get("error.user.notFound", userId)));
        User actor = requireCurrentUser();

        if (!active) {
            if (actor.getId().equals(target.getId())) {
                throw new BadRequestException(messages.get("user.status.selfDeactivate"));
            }
            if (target.getRole() == UserRole.SUPER_ADMIN) {
                throw new ForbiddenException(messages.get("user.status.superAdmin"));
            }
        }

        target.setIsActive(active);
        userRepository.save(target);

        int revoked = 0;
        if (!active) {
            revoked = refreshTokenRepository.revokeAllByUserId(target.getId());
        }
        log.info("User {}: target={} (id={}), by={}, revokedSessions={}",
            active ? "activated" : "deactivated",
            target.getUsername(), target.getId(), actor.getUsername(), revoked);

        return toResponse(target);
    }

    // ------------------------------------------------------------------
    // Yordamchi
    // ------------------------------------------------------------------

    /** Email va telefon takrorlanmasin (bo'sh bo'lmasa). */
    private void validateEmailAndPhone(String email, String phone, Long excludeUserId) {
        String cleanEmail = normalizeBlank(email);
        if (cleanEmail != null) {
            userRepository.findByEmail(cleanEmail)
                .filter(u -> excludeUserId == null || !u.getId().equals(excludeUserId))
                .ifPresent(u -> {
                    throw new BadRequestException(messages.get("user.email.taken", cleanEmail));
                });
        }
        String cleanPhone = normalizeBlank(phone);
        if (cleanPhone != null) {
            userRepository.findByPhone(cleanPhone)
                .filter(u -> excludeUserId == null || !u.getId().equals(excludeUserId))
                .ifPresent(u -> {
                    throw new BadRequestException(messages.get("user.phone.taken", cleanPhone));
                });
        }
    }

    /** PUT /api/users/{id} uchun — o'zidan boshqada takrorlanmasin. */
    public void validateEmailAndPhoneForUpdate(String email, String phone, Long userId) {
        validateEmailAndPhone(email, phone, userId);
    }

    private static String normalizeBlank(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private User requireCurrentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null) {
            throw new ForbiddenException(messages.get("error.auth.required"));
        }
        return userRepository.findByUsername(auth.getName())
            .orElseThrow(() -> new ForbiddenException(messages.get("error.auth.currentUserNotFound")));
    }

    private String currentUsername() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null ? auth.getName() : "system";
    }

    private void linkTeacherToUser(Teacher teacher, User user) {
        if (teacher.getUser() == null || !teacher.getUser().getId().equals(user.getId())) {
            teacher.setUser(user);
            teacherRepository.save(teacher);
        }
    }

    public UserResponse toResponse(User u) {
        return UserResponse.builder()
            .id(u.getId())
            .username(u.getUsername())
            .email(u.getEmail())
            .firstName(u.getFirstName())
            .lastName(u.getLastName())
            .phone(u.getPhone())
            .role(u.getRole())
            .isActive(u.getIsActive())
            .lastLogin(u.getLastLogin())
            .createdAt(u.getCreatedAt())
            .photoUrl(u.getPhotoUrl())
            .build();
    }
}
