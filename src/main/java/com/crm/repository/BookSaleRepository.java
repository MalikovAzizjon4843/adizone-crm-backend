package com.crm.repository;

import com.crm.entity.BookSale;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Optional;

public interface BookSaleRepository extends JpaRepository<BookSale, Long>, JpaSpecificationExecutor<BookSale> {

    Optional<BookSale> findByIdempotencyKey(String idempotencyKey);
}
