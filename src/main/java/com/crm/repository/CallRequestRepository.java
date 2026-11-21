package com.crm.repository;

import com.crm.entity.CallRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

public interface CallRequestRepository extends JpaRepository<CallRequest, Long> {

    List<CallRequest> findByUserIdAndStatusInOrderByIdDesc(Long userId, Collection<CallRequest.Status> statuses);

    /** Muddati o'tgan ochiq so'rovlar (PENDING / DELIVERED). */
    @Query(value = """
        SELECT r FROM CallRequest r
        WHERE r.status IN :statuses AND r.expiresAt <= :now
        """)
    List<CallRequest> findOpenExpired(@Param(value = "statuses") Collection<CallRequest.Status> statuses,
                                      @Param(value = "now") LocalDateTime now);

    default List<CallRequest> findOpenExpired(LocalDateTime now) {
        return findOpenExpired(java.util.List.of(CallRequest.Status.PENDING, CallRequest.Status.DELIVERED), now);
    }
}
