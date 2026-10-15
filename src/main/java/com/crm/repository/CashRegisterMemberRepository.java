package com.crm.repository;

import com.crm.entity.CashRegisterMember;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface CashRegisterMemberRepository extends JpaRepository<CashRegisterMember, Long> {

    List<CashRegisterMember> findByCashRegisterId(Long cashRegisterId);

    List<CashRegisterMember> findByCashRegisterIdIn(Collection<Long> cashRegisterIds);

    boolean existsByCashRegisterIdAndUserId(Long cashRegisterId, Long userId);

    boolean existsByUserId(Long userId);

    @Query(value = "SELECT m.cashRegisterId FROM CashRegisterMember m WHERE m.userId = :userId")
    List<Long> findRegisterIdsByUserId(@Param(value = "userId") Long userId);

    @Modifying
    @Query(value = "DELETE FROM CashRegisterMember m WHERE m.cashRegisterId = :registerId")
    int deleteByRegister(@Param(value = "registerId") Long registerId);

    /** "Bir admin — bitta kassa": shu foydalanuvchilarni boshqa kassalardan olib tashlaydi. */
    @Modifying
    @Query(value = "DELETE FROM CashRegisterMember m WHERE m.userId IN :userIds AND m.cashRegisterId <> :registerId")
    int deleteOtherMemberships(@Param(value = "userIds") Collection<Long> userIds,
                               @Param(value = "registerId") Long registerId);
}
