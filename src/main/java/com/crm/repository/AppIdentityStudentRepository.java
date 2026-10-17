package com.crm.repository;

import com.crm.entity.AppIdentity;
import com.crm.entity.AppIdentityStudent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface AppIdentityStudentRepository extends JpaRepository<AppIdentityStudent, Long> {

    List<AppIdentityStudent> findByIdentityIdOrderByIdAsc(Long identityId);

    @Modifying
    @Query("DELETE FROM AppIdentityStudent s WHERE s.identityId = :identityId")
    int deleteByIdentityId(@Param("identityId") Long identityId);

    /**
     * Bot bildirishnomalari (NotificationService.dispatch): o'quvchilarga ulangan FAOL, chat id si bor identity'lar.
     * Natija — {@code [studentId, AppIdentity]}.
     */
    @Query(value = """
        SELECT s.studentId, i FROM AppIdentityStudent s, AppIdentity i
        WHERE i.id = s.identityId AND s.studentId IN :studentIds AND s.relation IN :relations
          AND i.status = :status AND i.chatId IS NOT NULL
        ORDER BY s.studentId, i.id
        """)
    List<Object[]> findLinkedIdentities(@Param(value = "studentIds") Collection<Long> studentIds,
                                        @Param(value = "relations") Collection<AppIdentityStudent.Relation> relations,
                                        @Param(value = "status") AppIdentity.Status status);

    /**
     * Auditoriya bo'yicha (e'lon o'quvchi/ota-onaga): shu munosabatda kamida bitta o'quvchiga ulangan FAOL,
     * chat id si bor identity'lar.
     */
    @Query(value = """
        SELECT DISTINCT i FROM AppIdentityStudent s, AppIdentity i
        WHERE i.id = s.identityId AND s.relation IN :relations AND i.status = :status AND i.chatId IS NOT NULL
        """)
    List<AppIdentity> findIdentitiesByRelations(
        @Param(value = "relations") Collection<AppIdentityStudent.Relation> relations,
        @Param(value = "status") AppIdentity.Status status);

    /** Identity'ning munosabatlari (Mini App e'lonlari: SELF → o'quvchi, PARENT → ota-ona). */
    @Query(value = "SELECT DISTINCT s.relation FROM AppIdentityStudent s WHERE s.identityId = :identityId")
    List<AppIdentityStudent.Relation> findRelations(@Param(value = "identityId") Long identityId);
}
