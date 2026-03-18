package com.crm.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "classrooms")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Classroom extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "room_name", nullable = false, length = 100)
    private String roomName;

    @Column(name = "room_number", length = 20)
    private String roomNumber;

    private Integer capacity;

    @Column(length = 20)
    private String floor;

    @Column(name = "is_active")
    private Boolean isActive = true;
}
