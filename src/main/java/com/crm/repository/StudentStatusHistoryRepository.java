package com.crm.repository;

import com.crm.entity.StudentStatusHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface StudentStatusHistoryRepository extends JpaRepository<StudentStatusHistory, Long> {

    List<StudentStatusHistory> findByStudent_IdOrderByChangedAtDesc(Long studentId);
}
