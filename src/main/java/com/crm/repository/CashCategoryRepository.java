package com.crm.repository;

import com.crm.entity.CashCategory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CashCategoryRepository extends JpaRepository<CashCategory, String> {

    List<CashCategory> findAllByOrderByDirectionAscSortOrderAscCodeAsc();
}
