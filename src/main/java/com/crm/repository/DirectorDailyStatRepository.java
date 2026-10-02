package com.crm.repository;

import com.crm.entity.DirectorDailyStat;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface DirectorDailyStatRepository extends JpaRepository<DirectorDailyStat, DirectorDailyStat.Key> {

    List<DirectorDailyStat> findBySectionAndStatDateBetweenOrderByStatDateAsc(String section, LocalDate from, LocalDate to);
}
