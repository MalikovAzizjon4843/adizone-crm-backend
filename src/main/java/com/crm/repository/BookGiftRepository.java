package com.crm.repository;

import com.crm.entity.BookGift;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Optional;

public interface BookGiftRepository extends JpaRepository<BookGift, Long>, JpaSpecificationExecutor<BookGift> {

    Optional<BookGift> findByStudentId(Long studentId);

    boolean existsByStudentId(Long studentId);
}
