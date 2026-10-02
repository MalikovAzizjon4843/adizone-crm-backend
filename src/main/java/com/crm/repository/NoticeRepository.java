package com.crm.repository;

import com.crm.entity.Notice;
import com.crm.entity.enums.UserRole;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface NoticeRepository extends JpaRepository<Notice, Long> {

    /**
     * Bell feed: published + active + not expired.
     * Pass {@code LocalDate.now().atStartOfDay()} as {@code dayStart} so expiry is calendar-day based
     * ({@code expiresAt}'s date &gt;= today).
     */
    @Query("""
        SELECT n FROM Notice n
        WHERE n.isActive = true
          AND n.isPublished = true
          AND (n.expiresAt IS NULL OR n.expiresAt >= :dayStart)
        ORDER BY COALESCE(n.publishedAt, n.createdAt) DESC
        """)
    List<Notice> findActiveNotices(@Param("dayStart") LocalDateTime dayStart, Pageable pageable);

    @Query("""
        SELECT n FROM Notice n
        WHERE n.isActive = true
          AND n.isPublished = true
          AND (n.expiresAt IS NULL OR n.expiresAt >= :dayStart)
        ORDER BY COALESCE(n.publishedAt, n.createdAt) DESC
        """)
    List<Notice> findActiveNotices(@Param("dayStart") LocalDateTime dayStart);

    @Query("""
        SELECT COUNT(n) FROM Notice n
        WHERE n.isActive = true
          AND n.isPublished = true
          AND (n.expiresAt IS NULL OR n.expiresAt >= :dayStart)
          AND NOT EXISTS (
            SELECT 1 FROM NoticeRead nr
            WHERE nr.notice = n AND nr.user.id = :userId
          )
        """)
    long countUnreadForUser(@Param("userId") Long userId, @Param("dayStart") LocalDateTime dayStart);

    // ── Rol bo'yicha auditoriya (phase5-audit N-01) ──────────────────────────
    // Ko'rinadi: rol targetRoles ichida YOKI targetRoles bo'sh va eski maydonlar cheklamaydi
    // (targetRole NULL/shu rol, publishedTo NULL/ALL/ROLES/shu rolning eski nomi — TEACHERS va h.k.).
    // Parametrlarning hammasi doim berilgan (null emas) — PostgreSQL'da tip muammosi yo'q.

    String VISIBLE = """
        (:role MEMBER OF n.targetRoles
         OR (n.targetRoles IS EMPTY
             AND (n.targetRole IS NULL OR n.targetRole = :roleName)
             AND (n.publishedTo IS NULL OR n.publishedTo IN ('ALL', 'ROLES') OR n.publishedTo = :legacyAudience)))
        """;

    String ACTIVE = """
        n.isActive = true
          AND n.isPublished = true
          AND (n.expiresAt IS NULL OR n.expiresAt >= :dayStart)
        """;

    @Query("SELECT n FROM Notice n WHERE " + ACTIVE + " AND " + VISIBLE
        + " ORDER BY COALESCE(n.publishedAt, n.createdAt) DESC")
    List<Notice> findVisibleActive(@Param("dayStart") LocalDateTime dayStart,
                                   @Param("role") UserRole role,
                                   @Param("roleName") String roleName,
                                   @Param("legacyAudience") String legacyAudience,
                                   Pageable pageable);

    @Query(value = "SELECT n FROM Notice n WHERE " + ACTIVE + " AND " + VISIBLE
        + " ORDER BY COALESCE(n.publishedAt, n.createdAt) DESC",
        countQuery = "SELECT COUNT(n) FROM Notice n WHERE " + ACTIVE + " AND " + VISIBLE)
    Page<Notice> pageVisibleActive(@Param("dayStart") LocalDateTime dayStart,
                                   @Param("role") UserRole role,
                                   @Param("roleName") String roleName,
                                   @Param("legacyAudience") String legacyAudience,
                                   Pageable pageable);

    @Query("SELECT COUNT(n) > 0 FROM Notice n WHERE n.id = :id AND " + ACTIVE + " AND " + VISIBLE)
    boolean isVisibleActive(@Param("id") Long id,
                            @Param("dayStart") LocalDateTime dayStart,
                            @Param("role") UserRole role,
                            @Param("roleName") String roleName,
                            @Param("legacyAudience") String legacyAudience);

    @Query("SELECT COUNT(n) FROM Notice n WHERE " + ACTIVE + " AND " + VISIBLE
        + " AND NOT EXISTS (SELECT 1 FROM NoticeRead nr WHERE nr.notice = n AND nr.user.id = :userId)")
    long countUnreadVisible(@Param("userId") Long userId,
                            @Param("dayStart") LocalDateTime dayStart,
                            @Param("role") UserRole role,
                            @Param("roleName") String roleName,
                            @Param("legacyAudience") String legacyAudience);
}
