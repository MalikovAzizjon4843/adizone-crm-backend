package com.crm.repository;

import com.crm.entity.BookReceipt;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;

public interface BookReceiptRepository extends JpaRepository<BookReceipt, Long>, JpaSpecificationExecutor<BookReceipt> {

    List<BookReceipt> findBySupplierId(Long supplierId);
}
