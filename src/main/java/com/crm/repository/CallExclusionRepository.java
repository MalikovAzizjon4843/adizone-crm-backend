package com.crm.repository;

import com.crm.entity.CallExclusion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CallExclusionRepository extends JpaRepository<CallExclusion, Long> {

    List<CallExclusion> findByUserIdOrderByIdAsc(Long userId);
}
