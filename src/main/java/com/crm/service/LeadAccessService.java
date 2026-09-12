package com.crm.service;

import com.crm.entity.Lead;
import com.crm.entity.User;
import com.crm.entity.enums.UserRole;
import com.crm.exception.ForbiddenException;
import com.crm.exception.ResourceNotFoundException;
import com.crm.repository.LeadRepository;
import com.crm.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

/**
 * Lidlar va vazifalar uchun ko'rish doirasini aniqlaydi —
 * {@link TeacherAccessService} naqshi bo'yicha.
 *
 * <p>Qoida ikkita:
 * <ul>
 *   <li>ADMIN, SUPER_ADMIN — hamma lidlar, cheklovsiz;</li>
 *   <li>SALES_MANAGER — faqat {@code assignedUser} o'zi bo'lgan lidlar.</li>
 * </ul>
 *
 * <p>Boshqa rol shu servisga yetib kelsa — 403. Bu "ochiq qolib ketish"
 * xavfini yopadi: {@code SecurityConfig} da yangi rol qo'shilsa ham u
 * jimgina hamma lidni ko'ra olmaydi, balki xato oladi.
 *
 * <p>ADMINISTRATOR roli ataylab yo'q — u ADMIN bilan bir xil bo'lgani uchun
 * olib tashlanadi, shuning uchun yangi kodga kiritilmaydi.
 */
@Service
@RequiredArgsConstructor
public class LeadAccessService {

    /** Vazifa mas'uli yoki lid operatori bo'la oladigan rollar. */
    private static final Set<UserRole> OPERATOR_ROLES =
        EnumSet.of(UserRole.SUPER_ADMIN, UserRole.ADMIN, UserRole.SALES_MANAGER);

    private static final Set<UserRole> FULL_ACCESS_ROLES =
        EnumSet.of(UserRole.SUPER_ADMIN, UserRole.ADMIN);

    private final UserRepository userRepository;
    private final LeadRepository leadRepository;

    public User getCurrentUserOrThrow() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null || "anonymousUser".equals(auth.getName())) {
            throw new ForbiddenException("Avtorizatsiya talab qilinadi");
        }
        return userRepository.findByUsername(auth.getName())
            .orElseThrow(() -> new ForbiddenException("Foydalanuvchi topilmadi"));
    }

    /** Autentifikatsiya bo'lmasa null — public endpointlar uchun. */
    public User currentUserOrNull() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null || "anonymousUser".equals(auth.getName())) {
            return null;
        }
        return userRepository.findByUsername(auth.getName()).orElse(null);
    }

    public boolean hasFullAccess() {
        User user = currentUserOrNull();
        return user != null && FULL_ACCESS_ROLES.contains(user.getRole());
    }

    public static boolean canBeOperator(UserRole role) {
        return role != null && OPERATOR_ROLES.contains(role);
    }

    public static Set<UserRole> operatorRoles() {
        return OPERATOR_ROLES;
    }

    /**
     * Cheklov doirasi: to'liq huquqda {@code empty}, SALES_MANAGER uchun
     * o'zining {@code userId} si. Ro'yxat so'rovlari shu qiymatni
     * {@code assignedUserId} filtri sifatida majburlab qo'yadi.
     */
    public Optional<Long> resolveOperatorScope() {
        User user = getCurrentUserOrThrow();
        if (FULL_ACCESS_ROLES.contains(user.getRole())) {
            return Optional.empty();
        }
        if (user.getRole() == UserRole.SALES_MANAGER) {
            return Optional.of(user.getId());
        }
        throw new ForbiddenException("Lidlarga ruxsat yo'q");
    }

    public void assertCanAccessLead(Long leadId) {
        if (hasFullAccess()) {
            return;
        }
        Lead lead = leadRepository.findById(leadId)
            .orElseThrow(() -> new ResourceNotFoundException("Lead", leadId));
        assertCanAccessLead(lead);
    }

    public void assertCanAccessLead(Lead lead) {
        Optional<Long> scope = resolveOperatorScope();
        if (scope.isEmpty()) {
            return;
        }
        Long ownerId = lead.getAssignedUser() != null ? lead.getAssignedUser().getId() : null;
        if (ownerId == null || !ownerId.equals(scope.get())) {
            throw new ForbiddenException("Bu lid sizga biriktirilmagan");
        }
    }
}
