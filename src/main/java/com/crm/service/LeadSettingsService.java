package com.crm.service;

import com.crm.audit.AuditAction;
import com.crm.audit.AuditContext;
import com.crm.audit.Audited;
import com.crm.dto.response.LeadDefaultAssigneeDto;
import com.crm.entity.Lead;
import com.crm.entity.Setting;
import com.crm.entity.User;
import com.crm.entity.enums.UserRole;
import com.crm.exception.CodedException;
import com.crm.repository.SettingRepository;
import com.crm.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.Set;

/**
 * Yangi lidlar uchun standart mas'ul — {@code settings.leads.default_assignee_user_id}
 * (mavjud kalit → matn jadvali, {@link CenterSettingsService} bilan bir mexanizm).
 *
 * <p>Kirgan lid ({@code /api/leads/public}, Meta, kanbandagi tez qo'shish) mas'ulsiz qolsa — shu
 * foydalanuvchiga biriktiriladi ({@link #assignIfUnassigned}); keyin bosqich {@code requires_task}
 * bo'lsa "Yangi lid: bog'lanish" vazifasi ham unga tushadi. Excel import bu sozlamani ISHLATMAYDI.
 * Sozlama bo'sh — avvalgi xulq (lid mas'ulsiz, vazifasiz).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LeadSettingsService {

    public static final String DEFAULT_ASSIGNEE_KEY = "leads.default_assignee_user_id";

    /** Standart mas'ul bo'la oladigan rollar (SALES_HEAD ataylab yo'q — buyurtmachi qarori). */
    static final Set<UserRole> ALLOWED_ROLES = Set.of(UserRole.SALES_MANAGER, UserRole.ADMIN, UserRole.SUPER_ADMIN);

    private final SettingRepository settingRepository;
    private final UserRepository userRepository;
    private final LeadAccessService leadAccessService;
    private final com.crm.dashboard.LeadFunnelTracker leadFunnelTracker;

    @Transactional(readOnly = true)
    public LeadDefaultAssigneeDto getDefaultAssignee() {
        return toDto(storedUserId().flatMap(userRepository::findById).orElse(null));
    }

    /**
     * {@code userId = null} — sozlamani tozalash. Aks holda foydalanuvchi faol va roli
     * SALES_MANAGER | ADMIN | SUPER_ADMIN bo'lishi shart — 400 {@code lead.defaultAssignee.invalid}.
     */
    @Transactional
    @Audited(action = AuditAction.UPDATE, entity = "Settings",
        summary = "'Lidlar uchun standart mas''ul: ' + (#result.fullName ?: 'yo''q')")
    public LeadDefaultAssigneeDto updateDefaultAssignee(Long userId) {
        User user = null;
        if (userId != null) {
            user = userRepository.findById(userId)
                .filter(LeadSettingsService::isEligible)
                .orElseThrow(() -> CodedException.badRequest("lead.defaultAssignee.invalid"));
        }
        Setting setting = settingRepository.findBySettingKey(DEFAULT_ASSIGNEE_KEY)
            .orElseGet(() -> Setting.builder().settingKey(DEFAULT_ASSIGNEE_KEY)
                .description("Yangi lidlar uchun standart mas'ul (users.id)").build());
        String value = userId != null ? userId.toString() : null;
        AuditContext.change(DEFAULT_ASSIGNEE_KEY, setting.getSettingValue(), value);
        setting.setSettingValue(value);
        setting.setUpdatedBy(leadAccessService.getCurrentUserOrThrow().getId());
        setting.setUpdatedAt(LocalDateTime.now());
        settingRepository.save(setting);
        return toDto(user);
    }

    /**
     * Ishlatish paytidagi standart mas'ul. Sozlangan foydalanuvchi keyinroq nofaol bo'lgan yoki
     * roli o'zgargan bo'lsa — bo'sh (ogohlantirish bilan), lid avvalgidek mas'ulsiz qoladi.
     */
    @Transactional(readOnly = true)
    public Optional<User> resolveDefaultAssignee() {
        Optional<Long> id = storedUserId();
        if (id.isEmpty()) {
            return Optional.empty();
        }
        Optional<User> user = userRepository.findById(id.get()).filter(LeadSettingsService::isEligible);
        if (user.isEmpty()) {
            log.warn("{}={} — foydalanuvchi topilmadi, nofaol yoki roli mos emas; e'tiborsiz",
                DEFAULT_ASSIGNEE_KEY, id.get());
        }
        return user;
    }

    /**
     * Kirgan lid mas'ulsiz bo'lsa — {@code preferred}, u bo'lmasa standart mas'ulga biriktiradi
     * (tayinlash tarixi — {@code lead_assignments}, {@code assignedBy = null}). Lidning o'z mas'uli
     * bo'lsa tegilmaydi. Qaytaradi — lidning yakuniy mas'uli (yo'q bo'lsa null).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public User assignIfUnassigned(Lead lead, User preferred) {
        if (lead.getAssignedUser() != null) {
            return lead.getAssignedUser();
        }
        User assignee = preferred != null ? preferred : resolveDefaultAssignee().orElse(null);
        if (assignee == null) {
            return null;
        }
        LocalDateTime at = leadFunnelTracker.now();
        lead.setAssignedUser(assignee);
        lead.setAssignedAt(at);
        leadFunnelTracker.onAssigned(lead, assignee, null, at);
        return assignee;
    }

    private Optional<Long> storedUserId() {
        return settingRepository.findBySettingKey(DEFAULT_ASSIGNEE_KEY)
            .map(Setting::getSettingValue)
            .map(String::trim)
            .filter(v -> !v.isEmpty())
            .flatMap(v -> {
                try {
                    return Optional.of(Long.valueOf(v));
                } catch (NumberFormatException e) {
                    log.warn("{} noto'g'ri qiymat: '{}'", DEFAULT_ASSIGNEE_KEY, v);
                    return Optional.empty();
                }
            });
    }

    private static boolean isEligible(User user) {
        return Boolean.TRUE.equals(user.getIsActive()) && ALLOWED_ROLES.contains(user.getRole());
    }

    private static LeadDefaultAssigneeDto toDto(User user) {
        if (user == null) {
            return LeadDefaultAssigneeDto.builder().build();
        }
        String name = ((user.getFirstName() != null ? user.getFirstName() : "") + " "
            + (user.getLastName() != null ? user.getLastName() : "")).trim();
        return LeadDefaultAssigneeDto.builder()
            .userId(user.getId())
            .fullName(name)
            .role(user.getRole())
            .active(Boolean.TRUE.equals(user.getIsActive()))
            .valid(isEligible(user))
            .build();
    }
}
