package com.crm.repository;

import com.crm.entity.StudyLevel;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface StudyLevelRepository extends JpaRepository<StudyLevel, Long> {

    List<StudyLevel> findAllByOrderByLanguageAscSortOrderAscCodeAsc();

    List<StudyLevel> findByLanguageIgnoreCaseAndCodeIgnoreCase(String language, String code);
}
