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

    @Column(length = 100)
    private String room;

    @Column(name = "max_students")
    private Integer maxStudents = 20;

    @Column(name = "current_students")
    private Integer currentStudents = 0;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date")
    private LocalDate endDate;

    @Enumerated(EnumType.STRING)
    private GroupStatus status = GroupStatus.ACTIVE;

    @Column(columnDefinition = "TEXT")
    private String notes;

    @OneToMany(mappedBy = "group", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @Builder.Default
    private List<GroupSchedule> schedules = new ArrayList<>();

    @OneToMany(mappedBy = "group", fetch = FetchType.LAZY)
    @Builder.Default
    private List<StudentGroup> studentGroups = new ArrayList<>();
}
