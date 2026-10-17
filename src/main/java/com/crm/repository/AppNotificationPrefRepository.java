package com.crm.repository;

import com.crm.entity.AppNotificationPref;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface AppNotificationPrefRepository extends JpaRepository<AppNotificationPref, Long> {

    List<AppNotificationPref> findByIdentityId(Long identityId);

    Optional<AppNotificationPref> findByIdentityIdAndTopic(Long identityId, String topic);

    /** dispatch: shu mavzuni O'CHIRGAN identity'lar (qolganlari — yoqiq). */
    @Query(value = """
        SELECT p.identityId FROM AppNotificationPref p
        WHERE p.identityId IN :identityIds AND p.topic = :topic AND p.enabled = false
        """)
    List<Long> findDisabledIdentityIds(@Param(value = "identityIds") Collection<Long> identityIds,
                                       @Param(value = "topic") String topic);

    @Modifying
    @Query(value = "DELETE FROM AppNotificationPref p WHERE p.identityId = :identityId")
    int deleteByIdentityId(@Param(value = "identityId") Long identityId);
}
