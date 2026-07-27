package com.crm.repository;

import com.crm.entity.BalanceTransaction;
import com.crm.entity.enums.BalanceTransactionType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface BalanceTransactionRepository extends JpaRepository<BalanceTransaction, Long> {

    List<BalanceTransaction> findByStudent_IdOrderByCreatedAtDesc(Long studentId);

    @Query("""
        SELECT t FROM BalanceTransaction t
        LEFT JOIN FETCH t.studentGroup sg
        LEFT JOIN FETCH sg.group
        LEFT JOIN FETCH t.createdBy
        WHERE t.student.id = :studentId
          AND (:groupId IS NULL OR sg.group.id = :groupId)
          AND (:from IS NULL OR t.createdAt >= :from)
          AND (:to IS NULL OR t.createdAt <= :to)
        ORDER BY t.createdAt DESC, t.id DESC
        """)
    List<BalanceTransaction> findHistory(
        @Param("studentId") Long studentId,
        @Param("groupId") Long groupId,
        @Param("from") LocalDateTime from,
        @Param("to") LocalDateTime to);

    @Query("""
        SELECT COALESCE(SUM(t.amount), 0) FROM BalanceTransaction t
        WHERE t.studentGroup.id = :studentGroupId
        """)
    BigDecimal sumAmountByStudentGroupId(@Param("studentGroupId") Long studentGroupId);

    boolean existsByStudentGroup_IdAndTypeAndReferenceId(
        Long studentGroupId, BalanceTransactionType type, Long referenceId);
}
