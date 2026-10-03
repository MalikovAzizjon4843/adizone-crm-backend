package com.crm.repository;

import com.crm.entity.AppIdentityStudent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface AppIdentityStudentRepository extends JpaRepository<AppIdentityStudent, Long> {

    List<AppIdentityStudent> findByIdentityIdOrderByIdAsc(Long identityId);

    @Modifying
    @Query("DELETE FROM AppIdentityStudent s WHERE s.identityId = :identityId")
    int deleteByIdentityId(@Param("identityId") Long identityId);
}
