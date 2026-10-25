package com.crm.repository;

import com.crm.entity.BookStockMovement;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BookStockMovementRepository extends JpaRepository<BookStockMovement, Long> {

    Page<BookStockMovement> findByBookItemIdOrderByIdDesc(Long bookItemId, Pageable pageable);

    long countByBookItemId(Long bookItemId);
}
