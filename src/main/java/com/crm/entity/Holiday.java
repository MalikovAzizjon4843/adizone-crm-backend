package com.crm.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** Dam olish kuni — rejadagi darslar sanalmaydi (director-dashboard §1.4, §3.5, G8). */
@Entity
@Table(name = "holidays")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Holiday {

    @Id
    @Column(name = "holiday_date")
    private LocalDate holidayDate;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
