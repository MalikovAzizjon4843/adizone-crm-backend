package com.crm.repository;

import com.crm.entity.SalaryRule;
import com.crm.entity.User;
import com.crm.entity.enums.UserRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface SalaryRuleRepository extends JpaRepository<SalaryRule, Long> {

    List<SalaryRule> findByIsActiveTrueOrderByRoleAscIdAsc();

    List<SalaryRule> findAllByOrderByRoleAscIdAsc();

    @Query("""
        SELECT r FROM SalaryRule r
        WHERE r.isActive = true
          AND r.user.id = :userId
          AND (r.effectiveFrom IS NULL OR r.effectiveFrom <= :asOf)
        ORDER BY r.effectiveFrom DESC NULLS LAST, r.id DESC
        """)
    List<SalaryRule> findActivePersonalRules(
        @Param("userId") Long userId,
        @Param("asOf") LocalDate asOf);

    @Query("""
        SELECT r FROM SalaryRule r
        WHERE r.isActive = true
          AND r.role = :role
          AND r.user IS NULL
          AND (r.effectiveFrom IS NULL OR r.effectiveFrom <= :asOf)
        ORDER BY r.effectiveFrom DESC NULLS LAST, r.id DESC
        """)
    List<SalaryRule> findActiveRoleRules(
        @Param("role") UserRole role,
        @Param("asOf") LocalDate asOf);

    default Optional<SalaryRule> resolveRule(User user, LocalDate asOf) {
        if (user == null) {
            return Optional.empty();
        }
        List<SalaryRule> personal = findActivePersonalRules(user.getId(), asOf);
        if (!personal.isEmpty()) {
            return Optional.of(personal.get(0));
        }
        if (user.getRole() == null) {
            return Optional.empty();
        }
        List<SalaryRule> roleRules = findActiveRoleRules(user.getRole(), asOf);
        return roleRules.isEmpty() ? Optional.empty() : Optional.of(roleRules.get(0));
    }
}
