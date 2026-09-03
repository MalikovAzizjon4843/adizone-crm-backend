package com.crm.audit;

import com.crm.entity.AuditLog;
import com.crm.repository.AuditLogRepository;
import com.crm.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Audit yozuvini saqlaydi — asosiy so'rov oqimidan TASHQARIDA.
 *
 * <p>{@code @Async} tufayli asosiy amal yozuvni kutmaydi. Chaqiruv har doim
 * tranzaksiya COMMIT bo'lgandan keyin keladi, shuning uchun REQUIRES_NEW
 * shunchaki yozuvga o'z tranzaksiyasini beradi.
 *
 * <p>Bu yerdagi har qanday xato yutiladi: audit yozilmagani uchun
 * allaqachon bajarilgan amal buzilmasligi kerak.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AuditRecorder {

    private final AuditLogRepository auditLogRepository;
    private final UserRepository userRepository;

    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(AuditLog draft) {
        try {
            // userId ni shu yerda aniqlaymiz — so'rov oqimida qo'shimcha SELECT bo'lmasin
            if (draft.getUserId() == null && draft.getUsername() != null) {
                userRepository.findByUsername(draft.getUsername())
                    .ifPresent(u -> draft.setUserId(u.getId()));
            }
            auditLogRepository.save(draft);
        } catch (Exception e) {
            log.error("Audit yozuvini saqlab bo'lmadi: action={} entity={} id={}",
                draft.getAction(), draft.getEntityType(), draft.getEntityId(), e);
        }
    }
}
