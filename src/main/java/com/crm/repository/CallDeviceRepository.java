package com.crm.repository;

import com.crm.entity.CallDevice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface CallDeviceRepository extends JpaRepository<CallDevice, Long> {

    Optional<CallDevice> findByTokenHash(String tokenHash);

    /** Faol qurilma(lar) — normal holatda bittadan ko'p emas (V90 qisman UNIQUE). */
    List<CallDevice> findByUserIdAndRevokedAtIsNullOrderByIdDesc(Long userId);

    List<CallDevice> findByDeviceUidAndRevokedAtIsNull(String deviceUid);

    List<CallDevice> findAllByOrderByRevokedAtAscLastSeenAtDescIdDesc();

    /** Shu telefonda (device_uid) shu xodimning eng oxirgi sinxron qilingan qo'ng'irog'i — qayta ro'yxatdan o'tishda. */
    @Query(value = """
        SELECT MAX(c.startedAt) FROM PhoneCall c, CallDevice d
        WHERE c.deviceId = d.id AND d.userId = :userId AND d.deviceUid = :deviceUid
        """)
    LocalDateTime lastCallStartedAt(@Param(value = "userId") Long userId, @Param(value = "deviceUid") String deviceUid);
}
