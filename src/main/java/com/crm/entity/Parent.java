package com.crm.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UuidGenerator;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "parents")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Parent extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @UuidGenerator
    @Column(unique = true, nullable = false, updatable = false)
    private UUID uuid;

    @Column(name = "full_name", nullable = false, length = 200)
    private String fullName;

    @Column(length = 20)
    private String phone;

    @Column(columnDefinition = "TEXT")
    private String address;

    @Column(length = 20)
    private String relation = "OTHER";

    @Column(name = "is_active")
    private Boolean isActive = true;

    @OneToMany(mappedBy = "parent", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @Builder.Default
    private List<StudentParent> studentParents = new ArrayList<>();
}
