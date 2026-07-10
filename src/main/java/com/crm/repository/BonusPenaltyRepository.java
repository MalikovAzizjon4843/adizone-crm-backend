package com.crm.repository;

import com.crm.entity.BonusPenalty;
import com.crm.entity.enums.BonusPenaltyStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface BonusPenaltyRepository extends JpaRepository<BonusPenalty, Long>,
        JpaSpecificationExecutor<BonusPenalty> {

    List<BonusPenalty> findByTeacherIdAndStatus(Long teacherId, BonusPenaltyStatus status);

    List<BonusPenalty> findByStudentIdAndStatus(Long studentId, BonusPenaltyStatus status);
}
