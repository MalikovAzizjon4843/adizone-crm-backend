package com.crm.repository;

import com.crm.entity.Notice;
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
}
