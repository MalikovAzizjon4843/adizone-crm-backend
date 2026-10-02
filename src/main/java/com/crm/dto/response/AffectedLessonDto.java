package com.crm.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/** Ta'tildagi o'qituvchining rejadagi darsi va (bo'lsa) "darsni X o'tdi" belgisi (§1.3, §2). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AffectedLessonDto {
    private Long groupId;
    private String groupName;
    private LocalDate lessonDate;
    private String startTime;
    private String endTime;
    private Long substitutionId;
    private Long substituteTeacherId;
    private String substituteTeacherName;
    private String substitutionStatus;
}
