package com.crm.repository;

import com.crm.entity.Lead;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface LeadRepository extends JpaRepository<Lead, Long>, JpaSpecificationExecutor<Lead> {

    Optional<Lead> findByPhone(String phone);

    /** Bosqich kodi bo'yicha — {@code lead_stages.code}. */
    long countByStatus(String status);

    /**
     * Bir nechta bosqich bo'yicha. Konvert bosqichlari ikkita bo'lgani uchun
     * ("online o'quvchi" va "offline o'quvchi") konvertatsiya hisoblari
     * bitta kod emas, to'plam bilan ishlaydi.
     */
    long countByStatusIn(java.util.Collection<String> statuses);

    @Query("SELECT COUNT(l) FROM Lead l WHERE l.createdAt >= :from AND l.createdAt <= :to")
    long countByCreatedAtBetween(
        @Param("from") LocalDateTime from,
        @Param("to") LocalDateTime to);

    long countByConvertedTrue();

    long countByAssignedUserIsNull();

    @Query("SELECT l.status, COUNT(l) FROM Lead l GROUP BY l.status")
    List<Object[]> countByStatusGrouped();

    /**
     * Kanban sarlavhalari: bosqich, lidlar soni, shulardan biriktirilmaganlari.
     *
     * <p>Uchinchi ustun shu yerda ataylab: "Неразобранное" hisoblagichini
     * alohida so'rovsiz berish uchun qatorlar bo'yicha yig'iladi. Ya'ni
     * butun kanban bitta GROUP BY bilan qoplanadi.
     */
    @Query("""
        SELECT l.status,
               COUNT(l),
               SUM(CASE WHEN l.assignedUser IS NULL THEN 1 ELSE 0 END)
        FROM Lead l
        GROUP BY l.status
        """)
    List<Object[]> countKanbanGrouped();

    /**
     * {@link #countKanbanGrouped} ning bitta operator uchun varianti.
     * Uchinchi ustun bu yerda doim 0 — operatorda biriktirilmagan lid
     * bo'lishi mumkin emas, lekin shakl bir xil qolsin.
     */
    @Query("""
        SELECT l.status,
               COUNT(l),
               SUM(CASE WHEN l.assignedUser IS NULL THEN 1 ELSE 0 END)
        FROM Lead l
        WHERE l.assignedUser.id = :userId
        GROUP BY l.status
        """)
    List<Object[]> countKanbanGroupedByUser(@Param("userId") Long userId);

    @Query("SELECT l.source, COUNT(l) FROM Lead l GROUP BY l.source")
    List<Object[]> countBySourceGrouped();

    @Query("""
        SELECT l.assignedUser.id,
               CONCAT(l.assignedUser.firstName, ' ', l.assignedUser.lastName),
               COUNT(l),
               SUM(CASE WHEN l.status IN :convertedCodes THEN 1 ELSE 0 END)
        FROM Lead l
        WHERE l.assignedUser IS NOT NULL
        GROUP BY l.assignedUser.id, l.assignedUser.firstName, l.assignedUser.lastName
        ORDER BY COUNT(l) DESC
        """)
    List<Object[]> countByOperatorGrouped(
        @Param("convertedCodes") java.util.Collection<String> convertedCodes);

    /** Batch: userId, assignedCount, convertedCount */
    @Query("""
        SELECT l.assignedUser.id,
               COUNT(l),
               SUM(CASE WHEN l.status IN :convertedCodes
                          OR l.converted = true THEN 1 ELSE 0 END)
        FROM Lead l
        WHERE l.assignedUser IS NOT NULL
          AND COALESCE(l.assignedAt, l.createdAt) >= :from
          AND COALESCE(l.assignedAt, l.createdAt) < :toExclusive
        GROUP BY l.assignedUser.id
        """)
    List<Object[]> countAssignedAndConvertedGroupedByUser(
        @Param("from") java.time.LocalDateTime from,
        @Param("toExclusive") java.time.LocalDateTime toExclusive,
        @Param("convertedCodes") java.util.Collection<String> convertedCodes);

    @Query("""
        SELECT COUNT(l) FROM Lead l
        WHERE l.assignedUser.id = :userId
          AND (l.status IN :convertedCodes OR l.converted = true)
          AND COALESCE(l.assignedAt, l.createdAt) >= :from
          AND COALESCE(l.assignedAt, l.createdAt) < :toExclusive
        """)
    long countConvertedByUserInRange(
        @Param("userId") Long userId,
        @Param("from") java.time.LocalDateTime from,
        @Param("toExclusive") java.time.LocalDateTime toExclusive,
        @Param("convertedCodes") java.util.Collection<String> convertedCodes);

    /**
     * Ochiq vazifasi yo'q, yopilmagan lidlar soni — amoCRM'dagi "Без задач".
     * Bu eng muhim ko'rsatkich: lid tizimda turgan, lekin uni oldinga
     * suradigan hech qanday rejalashtirilgan qadam yo'q.
     *
     * <p>{@code closedStatuses} — {@code LeadStageService.closedCodes()}.
     */
    @Query("""
        SELECT COUNT(l) FROM Lead l
        WHERE l.status NOT IN :closedStatuses
          AND NOT EXISTS (
            SELECT 1 FROM Task t
            WHERE t.lead = l
              AND t.status = com.crm.entity.enums.TaskStatus.OPEN)
        """)
    long countOpenLeadsWithoutTask(
        @Param("closedStatuses") java.util.Collection<String> closedStatuses);

    /** {@link #countOpenLeadsWithoutTask} ning bitta operator uchun varianti. */
    @Query("""
        SELECT COUNT(l) FROM Lead l
        WHERE l.status NOT IN :closedStatuses
          AND l.assignedUser.id = :userId
          AND NOT EXISTS (
            SELECT 1 FROM Task t
            WHERE t.lead = l
              AND t.status = com.crm.entity.enums.TaskStatus.OPEN)
        """)
    long countOpenLeadsWithoutTaskByUser(
        @Param("closedStatuses") java.util.Collection<String> closedStatuses,
        @Param("userId") Long userId);

    /**
     * Bosqichdagi lidlar soni — o'chirishga ruxsat berishdan oldin.
     *
     * <p>Native so'rov: {@code LeadStage} va {@code Lead} orasida JPA
     * bog'lanishi yo'q — bog'lovchi faqat matn ({@code code}).
     */
    @Query(value = "SELECT COUNT(*) FROM leads WHERE status = :code", nativeQuery = true)
    long countByStatusCode(@Param("code") String code);

    @Modifying
    @Query(value = "UPDATE leads SET status = :newStatus WHERE status = :oldStatus", nativeQuery = true)
    int migrateStatus(@Param("oldStatus") String oldStatus, @Param("newStatus") String newStatus);

    @Modifying
    @Query(value = """
        UPDATE leads SET status = 'CONVERTED'
        WHERE status = 'ENROLLED' AND converted = true
        """, nativeQuery = true)
    int migrateEnrolledConverted();

    @Modifying
    @Query(value = """
        UPDATE leads SET status = 'ONLINE_ENROLLED'
        WHERE status = 'ENROLLED'
          AND (converted IS NULL OR converted = false)
          AND UPPER(COALESCE(format, 'OFFLINE')) = 'ONLINE'
        """, nativeQuery = true)
    int migrateEnrolledOnline();

    @Modifying
    @Query(value = """
        UPDATE leads SET status = 'OFFLINE_ENROLLED'
        WHERE status = 'ENROLLED'
          AND (converted IS NULL OR converted = false)
          AND UPPER(COALESCE(format, 'OFFLINE')) != 'ONLINE'
        """, nativeQuery = true)
    int migrateEnrolledOffline();
}
