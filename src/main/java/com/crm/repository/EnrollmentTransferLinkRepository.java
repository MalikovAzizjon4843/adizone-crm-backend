package com.crm.repository;

import com.crm.entity.EnrollmentTransferLink;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface EnrollmentTransferLinkRepository extends JpaRepository<EnrollmentTransferLink, Long> {

    boolean existsByOldStudentGroupIdAndNewStudentGroupId(Long oldStudentGroupId, Long newStudentGroupId);

    List<EnrollmentTransferLink> findByOldStudentGroupIdIn(Collection<Long> oldStudentGroupIds);
}
