package com.crm.repository;

import com.crm.entity.CallRecording;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface CallRecordingRepository extends JpaRepository<CallRecording, Long> {

    Optional<CallRecording> findByCallId(Long callId);

    List<CallRecording> findByCallIdIn(Collection<Long> callIds);

    /** Retention: qo'ng'iroq {@code before} dan oldin bo'lgan, fayli hali bor yozuvlar. */
    @Query(value = """
        SELECT r FROM CallRecording r, PhoneCall c
        WHERE r.callId = c.id AND r.deletedAt IS NULL AND c.startedAt < :before
        ORDER BY r.id
        """)
    List<CallRecording> findExpired(@Param(value = "before") LocalDateTime before, Pageable pageable);
}
