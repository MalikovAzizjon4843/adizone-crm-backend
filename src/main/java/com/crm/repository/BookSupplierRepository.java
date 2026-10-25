package com.crm.repository;

import com.crm.entity.BookSupplier;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BookSupplierRepository extends JpaRepository<BookSupplier, Long> {

    List<BookSupplier> findAllByOrderByNameAsc();

    List<BookSupplier> findByNameIgnoreCase(String name);
}
