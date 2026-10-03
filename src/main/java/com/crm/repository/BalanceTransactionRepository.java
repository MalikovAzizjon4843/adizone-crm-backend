package com.crm.repository;

import com.crm.entity.BalanceTransaction;
import com.crm.entity.enums.BalanceTransactionType;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;

@Repository
public interface BalanceTransactionRepository extends JpaRepository<BalanceTransaction, Long>,
        JpaSpecificationExecutor<BalanceTransaction> {

    List<BalanceTransaction> findByStudent_IdOrderByCreatedAtDesc(Long studentId);

    /**
     * Balans tarixi (filtr — {@code BalanceTransactionService#getHistory} dagi Specification).
     *
     * <p>Avval {@code (:from IS NULL OR t.createdAt >= :from)} JPQL edi — PostgreSQL'da
     * {@code could not determine data type of parameter} bilan 500 berardi (H2 sezmaydi).
     * Ixtiyoriy filtrlar faqat Specification orqali: berilmagan shart SQL ga umuman tushmaydi.
     */
    @Override
    @EntityGraph(attributePaths = {"studentGroup", "studentGroup.group", "createdBy"})
    List<BalanceTransaction> findAll(Specification<BalanceTransaction> spec, Sort sort);

    @Query("""
        SELECT COALESCE(SUM(t.amount), 0) FROM BalanceTransaction t
        WHERE t.studentGroup.id = :studentGroupId
        """)
    BigDecimal sumAmountByStudentGroupId(@Param("studentGroupId") Long studentGroupId);

    /** Ro'yxatlar uchun batch: [studentGroupId, Σ amount] — yozuvi yo'q SG qatorda bo'lmaydi. */
    @Query("""
        SELECT t.studentGroup.id, COALESCE(SUM(t.amount), 0) FROM BalanceTransaction t
        WHERE t.studentGroup.id IN :ids
        GROUP BY t.studentGroup.id
        """)
    List<Object[]> sumAmountGroupedByStudentGroupIds(@Param("ids") java.util.Collection<Long> ids);

    boolean existsByStudentGroup_IdAndTypeAndReferenceId(
        Long studentGroupId, BalanceTransactionType type, Long referenceId);

    /** Bitta enrollment daftari — tekshiruv va ta'mirlash uchun (yozilish tartibida). */
    List<BalanceTransaction> findByStudentGroup_IdOrderByIdAsc(Long studentGroupId);

    /** FIFO va snapshot uchun: (effective_date, id) tartibida. */
    @Query("""
        SELECT t FROM BalanceTransaction t
        WHERE t.studentGroup.id = :sgId
        ORDER BY t.effectiveDate ASC, t.id ASC
        """)
    List<BalanceTransaction> findLedgerForFifo(@Param("sgId") Long sgId);

    List<BalanceTransaction> findByRelatedTxId(Long relatedTxId);

    List<BalanceTransaction> findByStudentGroup_IdAndReferenceIdAndTypeIn(
        Long studentGroupId, Long referenceId, java.util.Collection<BalanceTransactionType> types);

    List<BalanceTransaction> findByReferenceIdAndTypeIn(
        Long referenceId, java.util.Collection<BalanceTransactionType> types);

    List<BalanceTransaction> findByMigrationRunIdAndStudentGroup_Id(Long migrationRunId, Long studentGroupId);

    /** Migratsiya (§9.6): dry-run/tasdiqdan keyin ledger o'zgarmaganini tekshirish. */
    @Query("SELECT MAX(t.id) FROM BalanceTransaction t")
    Long findMaxId();
}
