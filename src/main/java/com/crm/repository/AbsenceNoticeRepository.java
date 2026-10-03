package com.crm.repository;

import com.crm.entity.AbsenceNotice;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface AbsenceNoticeRepository extends JpaRepository<AbsenceNotice, Long> {

    List<AbsenceNotice> findByGroupIdAndLessonDateAndStatusOrderByIdAsc(Long groupId, LocalDate lessonDate,
                                                                       AbsenceNotice.Status status);

    boolean existsByStudentIdAndGroupIdAndLessonDateAndStatus(Long studentId, Long groupId, LocalDate lessonDate,
                                                              AbsenceNotice.Status status);

    List<AbsenceNotice> findByIdentityIdOrderByCreatedAtDescIdDesc(Long identityId);

    List<AbsenceNotice> findByIdentityIdAndStudentIdOrderByCreatedAtDescIdDesc(Long identityId, Long studentId);
}
