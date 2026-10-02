package com.crm.repository;

import com.crm.entity.LessonException;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface LessonExceptionRepository extends JpaRepository<LessonException, Long> {

    List<LessonException> findByLessonDateBetween(LocalDate from, LocalDate to);

    List<LessonException> findByMovedToBetween(LocalDate from, LocalDate to);

    List<LessonException> findByGroupIdOrderByLessonDateDesc(Long groupId);

    List<LessonException> findByGroupIdAndLessonDate(Long groupId, LocalDate lessonDate);

    List<LessonException> findByGroupIdAndMovedTo(Long groupId, LocalDate movedTo);
}
