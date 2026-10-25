package com.crm.repository;

import com.crm.entity.BookItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;

public interface BookItemRepository extends JpaRepository<BookItem, Long>, JpaSpecificationExecutor<BookItem> {

    List<BookItem> findByTitleIgnoreCaseAndKind(String title, com.crm.entity.enums.BookKind kind);
}
