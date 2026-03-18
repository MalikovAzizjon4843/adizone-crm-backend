package com.crm.repository;

import com.crm.entity.Course;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface CourseRepository extends JpaRepository<Course, Long> {
    List<Course> findByIsActiveTrue();
    boolean existsByCourseName(String courseName);
}
