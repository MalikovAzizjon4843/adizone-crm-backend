package com.crm.repository;

import com.crm.entity.BookSaleLine;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface BookSaleLineRepository extends JpaRepository<BookSaleLine, Long> {

    List<BookSaleLine> findBySaleIdOrderByIdAsc(Long saleId);

    List<BookSaleLine> findBySaleIdIn(Collection<Long> saleIds);
}
