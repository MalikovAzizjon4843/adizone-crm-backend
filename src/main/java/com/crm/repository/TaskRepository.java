package com.crm.repository;

import com.crm.entity.Task;
import com.crm.entity.enums.TaskStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface TaskRepository extends JpaRepository<Task, Long>, JpaSpecificationExecutor<Task> {

    /**
     * Berilgan lidlarning barcha OCHIQ vazifalari, muddati bo'yicha o'sish tartibida.
     * Chaqiruvchi har bir lid uchun BIRINCHI yozuvni oladi — shu sababli
     * sarlavha ham qo'shimcha so'rovsiz keladi.
     *
     * <p>Bitta so'rov, {@code idx_tasks_lead} indeksidan foydalanadi.
     * Sahifa 100 lidda ham arzon.
     */
    @Query("""
        SELECT t FROM Task t
        WHERE t.lead.id IN :leadIds
          AND t.status = com.crm.entity.enums.TaskStatus.OPEN
        ORDER BY t.dueAt ASC
        """)
    List<Task> findOpenByLeadIds(@Param("leadIds") List<Long> leadIds);

    /**
     * Ro'yxat so'rovlari uchun mas'ul/muallif/lid/o'quvchi bir so'rovda keladi.
     * Hammasi ManyToOne — sahifalash bazada qoladi, xotirada emas.
     */
    @Override
    @EntityGraph(attributePaths = {"assignedTo", "createdBy", "lead", "student", "completedBy"})
    Page<Task> findAll(Specification<Task> spec, Pageable pageable);

    /** Lid kartasi uchun: ochiqlar yuqorida, keyin yopilganlar yangidan eskiga. */
    @EntityGraph(attributePaths = {"assignedTo", "createdBy", "completedBy"})
    @Query("""
        SELECT t FROM Task t
        WHERE t.lead.id = :leadId
        ORDER BY
          CASE WHEN t.status = com.crm.entity.enums.TaskStatus.OPEN THEN 0 ELSE 1 END,
          t.dueAt DESC
        """)
    List<Task> findByLeadIdOrdered(@Param("leadId") Long leadId);

    boolean existsByLead_IdAndStatus(Long leadId, TaskStatus status);

    /**
     * Ochiq vazifalar taqsimoti: [muddati o'tgan, bugungi, kelajakdagi].
     * Chegaralar kesishmaydi — muddati o'tgan &lt; hozir &le; bugun &lt; ertaga &le; kelajak,
     * shuning uchun uchta son jami ochiq vazifalar soniga teng bo'ladi.
     */
    @Query("""
        SELECT
          SUM(CASE WHEN t.dueAt < :now THEN 1 ELSE 0 END),
          SUM(CASE WHEN t.dueAt >= :now AND t.dueAt < :tomorrow THEN 1 ELSE 0 END),
          SUM(CASE WHEN t.dueAt >= :tomorrow THEN 1 ELSE 0 END)
        FROM Task t
        WHERE t.status = com.crm.entity.enums.TaskStatus.OPEN
        """)
    List<Object[]> countOpenBuckets(
        @Param("now") LocalDateTime now,
        @Param("tomorrow") LocalDateTime tomorrow);

    /**
     * {@link #countOpenBuckets} ning bitta operator uchun varianti.
     *
     * <p>Alohida metod: {@code (:userId IS NULL OR ...)} shakli PostgreSQL'da
     * null parametr tipini aniqlay olmay xato beradi.
     */
    @Query("""
        SELECT
          SUM(CASE WHEN t.dueAt < :now THEN 1 ELSE 0 END),
          SUM(CASE WHEN t.dueAt >= :now AND t.dueAt < :tomorrow THEN 1 ELSE 0 END),
          SUM(CASE WHEN t.dueAt >= :tomorrow THEN 1 ELSE 0 END)
        FROM Task t
        WHERE t.status = com.crm.entity.enums.TaskStatus.OPEN
          AND t.assignedTo.id = :userId
        """)
    List<Object[]> countOpenBucketsByUser(
        @Param("now") LocalDateTime now,
        @Param("tomorrow") LocalDateTime tomorrow,
        @Param("userId") Long userId);

    /** userId, ism, muddati o'tgan, bugungi, jami ochiq. */
    @Query("""
        SELECT t.assignedTo.id,
               CONCAT(t.assignedTo.firstName, ' ', t.assignedTo.lastName),
               SUM(CASE WHEN t.dueAt < :now THEN 1 ELSE 0 END),
               SUM(CASE WHEN t.dueAt >= :now AND t.dueAt < :tomorrow THEN 1 ELSE 0 END),
               COUNT(t)
        FROM Task t
        WHERE t.status = com.crm.entity.enums.TaskStatus.OPEN
        GROUP BY t.assignedTo.id, t.assignedTo.firstName, t.assignedTo.lastName
        ORDER BY COUNT(t) DESC
        """)
    List<Object[]> countOpenGroupedByUser(
        @Param("now") LocalDateTime now,
        @Param("tomorrow") LocalDateTime tomorrow);
}
