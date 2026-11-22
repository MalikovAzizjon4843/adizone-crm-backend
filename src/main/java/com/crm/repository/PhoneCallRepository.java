package com.crm.repository;

import com.crm.entity.PhoneCall;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface PhoneCallRepository extends JpaRepository<PhoneCall, Long>, JpaSpecificationExecutor<PhoneCall> {

    Optional<PhoneCall> findByDeviceIdAndDeviceCallKey(Long deviceId, String deviceCallKey);

    List<PhoneCall> findByDeviceIdAndDeviceCallKeyIn(Long deviceId, Collection<String> keys);

    /** Shu raqam bilan eng oxirgi izohli qo'ng'iroq — kiruvchi qo'ng'iroq kartasi. */
    @Query(value = """
        SELECT c FROM PhoneCall c
        WHERE c.phoneNormalized = :phone AND c.note IS NOT NULL
        ORDER BY c.startedAt DESC, c.id DESC
        """)
    List<PhoneCall> findLastNoted(@Param(value = "phone") String phone, Pageable pageable);

    @Query(value = """
        SELECT c FROM PhoneCall c
        WHERE c.phoneNormalized = :phone
        ORDER BY c.startedAt DESC, c.id DESC
        """)
    List<PhoneCall> findLast(@Param(value = "phone") String phone, Pageable pageable);

    /** Davr ichidagi qo'ng'iroqlar (statistika) — barcha xodimlar. */
    /** Har xodimning oxirgi qo'ng'irog'i: {@code [userId, maxStartedAt]} ({@code GET /api/calls/users}). */
    @Query(value = "SELECT c.userId, MAX(c.startedAt) FROM PhoneCall c GROUP BY c.userId")
    List<Object[]> lastCallByUser();

    List<PhoneCall> findByStartedAtGreaterThanEqualAndStartedAtLessThan(LocalDateTime from, LocalDateTime to);

    List<PhoneCall> findByUserIdAndStartedAtGreaterThanEqualAndStartedAtLessThan(Long userId, LocalDateTime from,
                                                                                LocalDateTime to);

    /**
     * Javobsizga qayta aloqa: raqam bo'yicha eng oxirgi "aloqa" — chiquvchi qo'ng'iroq (urinish ham) yoki javob
     * berilgan kiruvchi. Statistika: javobsizdan KEYIN aloqa bo'lmagan raqamlar.
     */
    @Query(value = """
        SELECT c.phoneNormalized, MAX(c.startedAt) FROM PhoneCall c
        WHERE c.phoneNormalized IN :phones
          AND (c.direction = :outbound OR (c.direction = :inbound AND c.durationSec > 0))
        GROUP BY c.phoneNormalized
        """)
    List<Object[]> lastContactByPhone(@Param(value = "phones") Collection<String> phones,
                                      @Param(value = "outbound") PhoneCall.Direction outbound,
                                      @Param(value = "inbound") PhoneCall.Direction inbound);

    default List<Object[]> lastContactByPhone(Collection<String> phones) {
        return lastContactByPhone(phones, PhoneCall.Direction.OUT, PhoneCall.Direction.IN);
    }
}
