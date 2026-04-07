package com.crm.repository;

import com.crm.entity.Leave;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface LeaveRepository extends JpaRepository<Leave, Long> {
    Page<Leave> findAll(Pageable pageable);
    Page<Leave> findByStatus(String status, Pageable pageable);
    Page<Leave> findByRequesterId(Long requesterId, Pageable pageable);
    List<Leave> findByStatus(String status);

    @Query("SELECT COUNT(l) FROM Leave l WHERE l.status = 'PENDING'")
    long countPending();
}
