package com.crm.repository;

import com.crm.entity.Lead;
import com.crm.entity.enums.LeadStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface LeadRepository extends JpaRepository<Lead, Long>, JpaSpecificationExecutor<Lead> {

    Optional<Lead> findByPhone(String phone);

    long countByStatus(LeadStatus status);

    long countByAssignedUserIsNull();

    @Query("SELECT l.status, COUNT(l) FROM Lead l GROUP BY l.status")
    List<Object[]> countByStatusGrouped();

    @Query("SELECT l.source, COUNT(l) FROM Lead l GROUP BY l.source")
    List<Object[]> countBySourceGrouped();

    @Query("""
        SELECT l.assignedUser.id,
               CONCAT(l.assignedUser.firstName, ' ', l.assignedUser.lastName),
               COUNT(l),
               SUM(CASE WHEN l.status = com.crm.entity.enums.LeadStatus.CONVERTED THEN 1 ELSE 0 END)
        FROM Lead l
        WHERE l.assignedUser IS NOT NULL
        GROUP BY l.assignedUser.id, l.assignedUser.firstName, l.assignedUser.lastName
        ORDER BY COUNT(l) DESC
        """)
    List<Object[]> countByOperatorGrouped();

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
