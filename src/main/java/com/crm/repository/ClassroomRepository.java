package com.crm.repository;

import com.crm.entity.Classroom;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ClassroomRepository extends JpaRepository<Classroom, Long> {
    Page<Classroom> findByIsActiveTrue(Pageable pageable);

    Optional<Classroom> findFirstByRoomNumberIgnoreCase(String roomNumber);
}
