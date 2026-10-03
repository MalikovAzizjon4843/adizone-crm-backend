package com.crm.repository;

import com.crm.entity.Student;
import com.crm.entity.StudentParent;
import com.crm.entity.enums.StudentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface StudentParentRepository extends JpaRepository<StudentParent, Long> {

    List<StudentParent> findByStudentId(Long studentId);
    List<StudentParent> findByParentId(Long parentId);
    Optional<StudentParent> findByStudentIdAndParentId(Long studentId, Long parentId);
    boolean existsByStudentIdAndParentId(Long studentId, Long parentId);

    /** Ota-onalarning farzandlari, berilgan holatdan tashqari (Mini App bog'lash, telegram-platform §3.4). */
    @Query("SELECT sp.student FROM StudentParent sp WHERE sp.parent.id IN :parentIds AND sp.student.status <> :excluded")
    List<Student> findStudentsByParentIds(@Param("parentIds") Collection<Long> parentIds,
                                          @Param("excluded") StudentStatus excluded);
}
