package com.crm.repository;

import com.crm.entity.BookReceiptLine;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface BookReceiptLineRepository extends JpaRepository<BookReceiptLine, Long> {

    List<BookReceiptLine> findByReceiptIdOrderByIdAsc(Long receiptId);

    List<BookReceiptLine> findByReceiptIdIn(Collection<Long> receiptIds);
}
