package com.crm.repository;

import com.crm.entity.Leave;
import com.crm.entity.enums.LeaveStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

@Repository
public interface LeaveRepository extends JpaRepository<Leave, Long>, JpaSpecificationExecutor<Leave> {

    Page<Leave> findAll(Pageable pageable);

    long countByStatus(LeaveStatus status);

    default long countPending() {
        return countByStatus(LeaveStatus.PENDING);
    }

    /**
     * Xodimning {@code [from, to]} bilan kesishadigan berilgan holatdagi ta'tillari ({@code excludeId} dan
     * tashqari; yo'q bo'lsa {@code -1}). Kesishuv taqiqi va payroll uchun.
     */
    @Query("""
        SELECT l FROM Leave l
        WHERE l.user.id = :userId AND l.status IN :statuses
          AND l.fromDate <= :to AND l.toDate >= :from AND l.id <> :excludeId
        ORDER BY l.fromDate, l.id
        """)
    List<Leave> findOverlapping(@Param("userId") Long userId, @Param("from") LocalDate from,
                                @Param("to") LocalDate to, @Param("statuses") Collection<LeaveStatus> statuses,
                                @Param("excludeId") Long excludeId);

    /** O'qituvchi bog'langan, shu sanani qamragan ta'tillar (ON_LEAVE job). */
    @Query("""
        SELECT l FROM Leave l JOIN FETCH l.teacher
        WHERE l.status = :status AND l.fromDate <= :date AND l.toDate >= :date
        """)
    List<Leave> findTeacherLeavesCovering(@Param("status") LeaveStatus status, @Param("date") LocalDate date);

    /** Shu sanadan oldin tugagan APPROVED ta'tili bor o'qituvchilar (ON_LEAVE → ACTIVE qaytarish). */
    @Query("""
        SELECT DISTINCT l.teacher.id FROM Leave l
        WHERE l.status = :status AND l.teacher IS NOT NULL AND l.toDate < :date
        """)
    List<Long> findTeacherIdsWithLeaveEndedBefore(@Param("status") LeaveStatus status, @Param("date") LocalDate date);
}
