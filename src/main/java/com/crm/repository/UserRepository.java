package com.crm.repository;

import com.crm.entity.User;
import com.crm.entity.enums.UserRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long>, JpaSpecificationExecutor<User> {
    Optional<User> findByUsername(String username);
    Optional<User> findByEmail(String email);
    boolean existsByUsername(String username);
    boolean existsByUsernameIgnoreCase(String username);
    boolean existsByEmail(String email);
    boolean existsByEmailIgnoreCase(String email);
    boolean existsByPhone(String phone);
    Optional<User> findByPhone(String phone);
    List<User> findByIsActiveTrue();
    List<User> findByRole(UserRole role);

    List<User> findByRoleInAndIsActiveTrueOrderByFirstNameAscLastNameAsc(Collection<UserRole> roles);
    List<User> findByFirstNameAndLastNameAndRole(
        String firstName, String lastName, UserRole role);
    Optional<User> findByPhoneAndRole(String phone, UserRole role);

    /**
     * Oxirgi onlayn paytni yozadi — chat sessiyasi uzilganda.
     *
     * <p>Butun entity yuklanmaydi: hodisa har bir tab yopilganda keladi
     * va bitta ustun uchun SELECT ortiqcha. Yon ta'siri ham foydali —
     * {@code updatedAt} tegilmaydi, ya'ni oynani yopish xodim kartasini
     * "tahrirlangan" qilib ko'rsatmaydi.
     */
    @Modifying
    @Transactional
    @Query("UPDATE User u SET u.lastSeenAt = :lastSeenAt WHERE u.id = :userId")
    int touchLastSeenAt(@Param("userId") Long userId,
                        @Param("lastSeenAt") LocalDateTime lastSeenAt);

    /**
     * Mini App o'qituvchi rejimi (telegram-platform §11.4): telefon (users.phone yoki bog'langan o'qituvchi
     * profili telefoni) bo'yicha faol, berilgan roldagi xodimlar.
     */
    @Query("""
        SELECT DISTINCT u FROM User u
        WHERE u.role = :role AND u.isActive = true
          AND (u.phone IN :phones
               OR EXISTS (SELECT 1 FROM Teacher t WHERE t.user = u AND t.phone IN :phones))
        """)
    List<User> findActiveByRoleAndPhoneIn(@Param("role") UserRole role, @Param("phones") Collection<String> phones);
}
