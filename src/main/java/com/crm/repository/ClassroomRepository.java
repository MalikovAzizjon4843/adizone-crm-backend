package com.crm.repository;

import com.crm.entity.Classroom;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ClassroomRepository extends JpaRepository<Classroom, Long> {

    List<Classroom> findAllByOrderByRoomNumberAsc();

    Optional<Classroom> findByRoomNumberIgnoreCase(String roomNumber);

    boolean existsByRoomNumberIgnoreCase(String roomNumber);

    boolean existsByRoomNumberIgnoreCaseAndIdNot(String roomNumber, Long id);

    Optional<Classroom> findFirstByRoomNumberIgnoreCase(String roomNumber);
}
