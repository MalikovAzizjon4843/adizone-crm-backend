package com.crm.repository;

import com.crm.entity.MetaLeadFormQuestion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface MetaLeadFormQuestionRepository
        extends JpaRepository<MetaLeadFormQuestion, Long> {

    List<MetaLeadFormQuestion> findByForm_IdOrderBySortOrderAscIdAsc(Long formId);

    Optional<MetaLeadFormQuestion> findByForm_IdAndQuestionKey(Long formId, String questionKey);

    /** Ro'yxat sahifasi uchun: formId → savollar soni, bitta so'rov bilan. */
    @Query("SELECT q.form.id, COUNT(q) FROM MetaLeadFormQuestion q GROUP BY q.form.id")
    List<Object[]> countGroupedByForm();

    /** Mapping sozlanmagan savollar — sozlash sahifasidagi ogohlantirish. */
    @Query("""
        SELECT COUNT(q) FROM MetaLeadFormQuestion q
        WHERE q.form.id = :formId AND q.crmField IS NULL
        """)
    long countUnmapped(@Param("formId") Long formId);
}
