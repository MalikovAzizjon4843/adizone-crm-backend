package com.crm.repository;

import com.crm.entity.MetaWebhookEvent;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface MetaWebhookEventRepository extends JpaRepository<MetaWebhookEvent, Long> {

    boolean existsByLeadgenId(String leadgenId);

    Page<MetaWebhookEvent> findByStatusOrderByIdDesc(String status, Pageable pageable);

    Page<MetaWebhookEvent> findAllByOrderByIdDesc(Pageable pageable);

    long countByStatus(String status);

    /**
     * Qayta ishlashga tayyor eventlar — faqat ID lar.
     *
     * <p>Butun entity emas: ular alohida tranzaksiyalarda qaytadan
     * o'qiladi va bu yerdagi nusxa baribir eskirgan bo'lardi.
     */
    @Query("""
        SELECT e.id FROM MetaWebhookEvent e
        WHERE e.status = :status
          AND e.attempts < :maxAttempts
        ORDER BY e.id ASC
        """)
    List<Long> findPendingIds(
        @Param("status") String status,
        @Param("maxAttempts") int maxAttempts,
        Pageable pageable);
}
