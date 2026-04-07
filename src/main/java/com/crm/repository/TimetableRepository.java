package com.crm.repository;

import com.crm.entity.Timetable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TimetableRepository extends JpaRepository<Timetable, Long> {
    Page<Timetable> findAll(Pageable pageable);
    List<Timetable> findByClassEntityId(Long classId);
    List<Timetable> findByGroupId(Long groupId);
    List<Timetable> findByTeacherId(Long teacherId);
}
