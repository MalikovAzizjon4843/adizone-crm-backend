package com.crm.repository;

import com.crm.entity.GroupTransferBatch;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface GroupTransferBatchRepository extends JpaRepository<GroupTransferBatch, Long> {
    Optional<GroupTransferBatch> findByIdempotencyKey(String idempotencyKey);
}
