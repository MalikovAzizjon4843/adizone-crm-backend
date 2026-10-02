package com.crm.repository;

import com.crm.entity.AuditLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

@Repository
public interface AuditLogRepository
        extends JpaRepository<AuditLog, Long>, JpaSpecificationExecutor<AuditLog> {

    List<AuditLog> findByEntityTypeAndEntityIdOrderByCreatedAtDesc(String entityType, Long entityId);

    /**
     * Bitta obyektning bitta turdagi amallari — lid lentasida mas'ul
     * almashuvini olish uchun. Filtr aynan uchta ustun bo'yicha, ya'ni
     * boshqa maydon tahrirlari lentaga tushmaydi.
     */
    List<AuditLog> findByEntityTypeAndEntityIdAndActionOrderByCreatedAtDesc(
        String entityType, Long entityId, String action);

    @Query("SELECT DISTINCT a.action FROM AuditLog a ORDER BY a.action")
    List<String> findDistinctActions();

    @Query("SELECT DISTINCT a.entityType FROM AuditLog a WHERE a.entityType IS NOT NULL ORDER BY a.entityType")
    List<String> findDistinctEntityTypes();

    @Modifying
    @Query("DELETE FROM AuditLog a WHERE a.createdAt < :cutoff")
    int deleteOlderThan(@Param("cutoff") LocalDateTime cutoff);

    /** Moliyaviy BO'LMAGAN yozuvlar (amal ham, obyekt turi ham ro'yxatda emas). entityType NULL — oddiy. */
    @Modifying
    @Query("""
        DELETE FROM AuditLog a
        WHERE a.createdAt < :cutoff
          AND a.action NOT IN :financialActions
          AND (a.entityType IS NULL OR a.entityType NOT IN :financialTypes)
        """)
    int deleteRegularOlderThan(@Param("cutoff") LocalDateTime cutoff,
                               @Param("financialActions") Collection<String> financialActions,
                               @Param("financialTypes") Collection<String> financialTypes);

    /** Moliyaviy yozuvlar (amal YOKI obyekt turi ro'yxatda). */
    @Modifying
    @Query("""
        DELETE FROM AuditLog a
        WHERE a.createdAt < :cutoff
          AND (a.action IN :financialActions OR a.entityType IN :financialTypes)
        """)
    int deleteFinancialOlderThan(@Param("cutoff") LocalDateTime cutoff,
                                 @Param("financialActions") Collection<String> financialActions,
                                 @Param("financialTypes") Collection<String> financialTypes);
}
