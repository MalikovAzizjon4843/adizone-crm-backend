package com.crm.service;

import com.crm.dto.response.OnboardingItemResponse;
import com.crm.entity.UserOnboarding;
import com.crm.exception.CodedException;
import com.crm.repository.UserOnboardingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Xodimning onboarding holati — qaysi turlarni ko'rgan (V67, {@code user_onboarding}). Har amal faqat
 * JORIY foydalanuvchining yozuvlari ustida: id so'rovdan emas, SecurityContext'dan olinadi.
 */
@Service
@RequiredArgsConstructor
public class UserOnboardingService {

    /**
     * Kalit: 1–80 belgi, kichik lotin harf/raqam bo'laklari, orasida bitta {@code . _ -}
     * (masalan {@code dashboard.intro}, {@code leads.kanban-v2}). V67 dagi CHECK bilan bir xil.
     */
    public static final Pattern KEY_PATTERN = Pattern.compile("[a-z0-9]+(?:[._-][a-z0-9]+)*");
    public static final int MAX_KEY_LENGTH = 80;
    /** Bir xodimga eng ko'p kalit — cheksiz yozishga qarshi. */
    public static final int MAX_KEYS_PER_USER = 200;

    private final UserOnboardingRepository repository;
    private final TeacherAccessService teacherAccessService;
    private final Clock billingClock;

    @Transactional(readOnly = true)
    public List<OnboardingItemResponse> list() {
        Long userId = currentUserId();
        return repository.findByUserIdOrderByTourKeyAsc(userId).stream().map(this::toDto).toList();
    }

    /** Idempotent: qayta belgilash xato emas, birinchi {@code seenAt} o'zgarmaydi. */
    @Transactional
    public OnboardingItemResponse markSeen(String rawKey) {
        String key = validKey(rawKey);
        Long userId = currentUserId();
        UserOnboarding.Key id = new UserOnboarding.Key(userId, key);
        if (!repository.existsById(id)) {
            if (repository.countByUserId(userId) >= MAX_KEYS_PER_USER) {
                throw new CodedException(HttpStatus.CONFLICT, "onboarding.limit", MAX_KEYS_PER_USER);
            }
            repository.insertIfAbsent(userId, key, LocalDateTime.now(billingClock));
        }
        return repository.findById(id).map(this::toDto).orElseThrow();
    }

    /** "Qayta ko'rish": barcha belgilarni o'chiradi. O'chirilganlar soni qaytadi. */
    @Transactional
    public int reset() {
        return repository.deleteByUserId(currentUserId());
    }

    static String validKey(String raw) {
        if (raw == null || raw.isEmpty() || raw.length() > MAX_KEY_LENGTH || !KEY_PATTERN.matcher(raw).matches()) {
            throw CodedException.badRequest("onboarding.key.invalid", MAX_KEY_LENGTH);
        }
        return raw;
    }

    private Long currentUserId() {
        return teacherAccessService.getCurrentUserOrThrow().getId();
    }

    private OnboardingItemResponse toDto(UserOnboarding o) {
        return new OnboardingItemResponse(o.getTourKey(), o.getSeenAt());
    }
}
