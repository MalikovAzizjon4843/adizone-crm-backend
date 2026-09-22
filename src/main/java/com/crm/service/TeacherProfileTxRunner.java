package com.crm.service;

import com.crm.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Bitta userning Teacher profilini ALOHIDA tranzaksiyada tiklaydi.
 *
 * <p>Alohida bean bo'lishi shart: {@code REQUIRES_NEW} faqat chaqiruv Spring
 * proksisidan o'tganda ishlaydi, bitta bean ichidagi {@code this.} chaqiruvi
 * tranzaksiya annotatsiyasini butunlay chetlab o'tadi.
 */
@Service
@RequiredArgsConstructor
public class TeacherProfileTxRunner {

    private final UserRepository userRepository;
    private final TeacherService teacherService;

    /**
     * User ID bo'yicha qaytadan o'qiladi: tashqi siklda yuklangan entity
     * allaqachon yopilgan persistence context ga tegishli bo'lardi.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public TeacherService.ProfileOutcome ensureInNewTransaction(Long userId) {
        return userRepository.findById(userId)
            .map(teacherService::ensureTeacherProfile)
            .orElse(TeacherService.ProfileOutcome.SKIPPED);
    }
}
