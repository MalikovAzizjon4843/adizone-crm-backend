package com.crm.service;

import com.crm.dto.request.CourseRequest;
import com.crm.dto.response.CourseResponse;
import com.crm.entity.Course;
import com.crm.exception.DuplicateResourceException;
import com.crm.exception.ResourceNotFoundException;
import com.crm.repository.CourseRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CourseService {

    private final CourseRepository courseRepository;

    @Transactional(readOnly = true)
    public List<CourseResponse> getAllCourses(boolean activeOnly) {
        List<Course> courses = activeOnly
            ? courseRepository.findByIsActiveTrue()
            : courseRepository.findAll();
        return courses.stream().map(this::toResponse).collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public CourseResponse getCourseById(Long id) {
        return toResponse(findById(id));
    }

    @Transactional
    public CourseResponse createCourse(CourseRequest request) {
        if (courseRepository.existsByCourseName(request.getCourseName())) {
            throw new DuplicateResourceException("Course already exists: " + request.getCourseName());
        }
        Course course = Course.builder()
            .courseName(request.getCourseName())
            .description(request.getDescription())
            .durationMonths(request.getDurationMonths())
            .lessonsCount(request.getLessonsCount())
            .monthlyPrice(request.getMonthlyPrice())
            .isActive(true)
            .build();
        return toResponse(courseRepository.save(course));
    }

    @Transactional
    public CourseResponse updateCourse(Long id, CourseRequest request) {
        Course course = findById(id);
        courseRepository.findAll().stream()
            .filter(c -> c.getCourseName().equals(request.getCourseName()) && !c.getId().equals(id))
            .findFirst()
            .ifPresent(c -> { throw new DuplicateResourceException("Course name already used"); });

        course.setCourseName(request.getCourseName());
        course.setDescription(request.getDescription());
        course.setDurationMonths(request.getDurationMonths());
        course.setLessonsCount(request.getLessonsCount());
        course.setMonthlyPrice(request.getMonthlyPrice());
        return toResponse(courseRepository.save(course));
    }

    @Transactional
    public void deleteCourse(Long id) {
        Course course = findById(id);
        course.setIsActive(false);
        courseRepository.save(course);
    }

    public Course findById(Long id) {
        return courseRepository.findById(id)
            .orElseThrow(() -> new ResourceNotFoundException("Course", id));
    }

    private CourseResponse toResponse(Course c) {
        return CourseResponse.builder()
            .id(c.getId())
            .uuid(c.getUuid())
            .courseName(c.getCourseName())
            .description(c.getDescription())
            .durationMonths(c.getDurationMonths())
            .lessonsCount(c.getLessonsCount())
            .monthlyPrice(c.getMonthlyPrice())
            .isActive(c.getIsActive())
            .createdAt(c.getCreatedAt())
            .build();
    }
}
