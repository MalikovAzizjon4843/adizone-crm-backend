package com.crm.repository;

import com.crm.entity.BookSupplierPayment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BookSupplierPaymentRepository extends JpaRepository<BookSupplierPayment, Long> {

    List<BookSupplierPayment> findByReceiptIdOrderByIdAsc(Long receiptId);

    Optional<BookSupplierPayment> findByIdempotencyKey(String idempotencyKey);
}
