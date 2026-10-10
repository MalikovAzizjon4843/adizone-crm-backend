package com.crm.entity;

import com.crm.entity.enums.GroupStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UuidGenerator;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "groups")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Group extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @UuidGenerator
    @Column(unique = true, nullable = false, updatable = false)
    private UUID uuid;

    @Column(name = "group_name", nullable = false)
    private String groupName;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "course_id", nullable = false)
    private Course course;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "teacher_id")
    private Teacher teacher;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "classroom_id")
    private Classroom classroom;

    @Column(length = 100)
    private String room;

    @Column(name = "max_students")
    @Builder.Default
    private Integer maxStudents = 20;

    @Column(name = "current_students")
    @Builder.Default
    private Integer currentStudents = 0;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date")
    private LocalDate endDate;

    @Enumerated(EnumType.STRING)
    @Builder.Default
    private GroupStatus status = GroupStatus.ACTIVE;

    /**
     * Dars turi (V82): o'qituvchi ulushi shunga qarab; yangi guruhda standart — kursdan. Hech qachon null emas:
     * boshlang'ich GROUP, setter va @PrePersist/@PreUpdate null ni GROUP ga aylantiradi; {@code @ColumnDefault} —
     * Hibernate yaratgan sxemada ham V82 dagi kabi DEFAULT 'GROUP'.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "lesson_type", nullable = false, length = 20)
    @org.hibernate.annotations.ColumnDefault("'GROUP'")
    @Builder.Default
    private com.crm.entity.enums.LessonType lessonType = com.crm.entity.enums.LessonType.GROUP;

    @Column(columnDefinition = "TEXT")
    private String notes;

    @OneToMany(mappedBy = "group", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @Builder.Default
    private List<GroupSchedule> schedules = new ArrayList<>();

    @OneToMany(mappedBy = "group", fetch = FetchType.LAZY)
    @Builder.Default
    private List<StudentGroup> studentGroups = new ArrayList<>();

    /** null — GROUP: NOT NULL ustunga null yetib bormasin. */
    public void setLessonType(com.crm.entity.enums.LessonType lessonType) {
        this.lessonType = lessonType != null ? lessonType : com.crm.entity.enums.LessonType.GROUP;
    }

    public com.crm.entity.enums.LessonType getLessonType() {
        return lessonType != null ? lessonType : com.crm.entity.enums.LessonType.GROUP;
    }

    @PrePersist
    @PreUpdate
    protected void defaultLessonType() {
        if (lessonType == null) {
            lessonType = com.crm.entity.enums.LessonType.GROUP;
        }
    }
}
