package com.crm.repository;

import com.crm.entity.GroupScheduleDay;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface GroupScheduleDayRepository extends JpaRepository<GroupScheduleDay, Long> {

    List<GroupScheduleDay> findByGroup_IdOrderByDayOfWeekAsc(Long groupId);

    void deleteByGroup_Id(Long groupId);

    @Query("SELECT s FROM GroupScheduleDay s " +
           "WHERE s.room IS NOT NULL AND s.room.id = :roomId " +
           "AND s.dayOfWeek = :day " +
           "AND s.group.id <> :excludeGroupId " +
           "AND s.startTime IS NOT NULL AND s.endTime IS NOT NULL " +
           "AND :startTime IS NOT NULL AND :endTime IS NOT NULL " +
           "AND s.startTime < :endTime AND s.endTime > :startTime")
    List<GroupScheduleDay> findConflicts(
            @Param("roomId") Long roomId,
            @Param("day") String day,
            @Param("startTime") String startTime,
            @Param("endTime") String endTime,
            @Param("excludeGroupId") Long excludeGroupId);
}
