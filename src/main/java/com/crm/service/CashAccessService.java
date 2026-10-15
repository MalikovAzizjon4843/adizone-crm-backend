package com.crm.service;

import com.crm.billing.BillingAuth;
import com.crm.entity.CashRegister;
import com.crm.entity.enums.CashRegisterKind;
import com.crm.exception.CodedException;
import com.crm.exception.ResourceNotFoundException;
import com.crm.repository.CashRegisterMemberRepository;
import com.crm.repository.CashRegisterRepository;
import com.crm.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Kassaga kirish huquqi — YAGONA nuqta (docs/design/cash-and-books.md §1.2.1):
 * <ul>
 *   <li>SUPER_ADMIN — hamma kassa (ADMIN va DIRECTOR);</li>
 *   <li>boshqa rollar (ADMIN, ACCOUNTANT) — faqat a'zo bo'lgan ADMIN kassa. Boshqa kassa va Direktor
 *       kassasi — 404 (borligi ham sezilmasin); hech qaysi kassaga biriktirilmagan — 400
 *       {@code cash.noRegisterAssigned};</li>
 *   <li>autentifikatsiyasiz chaqiruv (ichki job) — tizim, cheklanmaydi: HTTP so'rovlar har doim autentifikatsiyali.</li>
 * </ul>
 * O'qish ({@code CashRegisterService} GET lari) va yozish ({@code recordIncome / recordExpense /
 * recordReversal}) shu yerda tekshiriladi — to'lov, imtihon, xarajat va boshqa oqimlar avtomatik qamraladi.
 */
@Service
@RequiredArgsConstructor
public class CashAccessService {

    private final CashRegisterRepository cashRegisterRepository;
    private final CashRegisterMemberRepository memberRepository;
    private final UserRepository userRepository;

    public boolean isDirector() {
        return BillingAuth.hasAnyRole("SUPER_ADMIN");
    }

    /** Ichki chaqiruv (job): autentifikatsiya yo'q. */
    public boolean isSystem() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth == null || auth instanceof AnonymousAuthenticationToken;
    }

    /** SA yoki tizim — cheklovsiz. */
    public boolean isUnrestricted() {
        return isSystem() || isDirector();
    }

    public Long currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null) {
            return null;
        }
        return userRepository.findByUsername(auth.getName()).map(u -> u.getId()).orElse(null);
    }

    /** Kassani yuklaydi va kirishni tekshiradi (yo'q yoki ruxsatsiz — 404). */
    public CashRegister requireAccessible(Long registerId) {
        CashRegister register = cashRegisterRepository.findById(registerId)
            .orElseThrow(() -> new ResourceNotFoundException("CashRegister", registerId));
        assertAccessible(register);
        return register;
    }

    public void assertAccessible(CashRegister register) {
        if (isUnrestricted()) {
            return;
        }
        Long userId = currentUserId();
        if (userId != null && register.getKind() == CashRegisterKind.ADMIN
                && memberRepository.existsByCashRegisterIdAndUserId(register.getId(), userId)) {
            return;
        }
        if (userId == null || !memberRepository.existsByUserId(userId)) {
            throw CodedException.badRequest("cash.noRegisterAssigned");
        }
        throw new ResourceNotFoundException("CashRegister", register.getId());
    }

    /** Ko'rinadigan kassalar: null — hammasi (SA / tizim), aks holda a'zo bo'lgan ADMIN kassalar id lari. */
    public List<Long> visibleRegisterIds() {
        if (isUnrestricted()) {
            return null;
        }
        Long userId = currentUserId();
        if (userId == null) {
            return List.of();
        }
        return memberRepository.findRegisterIdsByUserId(userId).stream()
            .filter(id -> cashRegisterRepository.findById(id)
                .map(r -> r.getKind() == CashRegisterKind.ADMIN).orElse(false))
            .toList();
    }

    /** Oylik, qaytarish, STRATEGIC xarajat, yetkazib beruvchiga to'lov — faqat Direktor kassasidan. */
    public void requireDirectorRegister(CashRegister register) {
        if (register.getKind() != CashRegisterKind.DIRECTOR) {
            throw CodedException.badRequest("cash.directorRegisterRequired");
        }
    }

    /** Servis darajasidagi SA tekshiruvi (controller {@code @PreAuthorize} ga qo'shimcha). */
    public void requireDirector() {
        if (!isUnrestricted()) {
            throw CodedException.forbidden("cash.directorOnly");
        }
    }
}
