package com.crm.repository;

import com.crm.entity.AppealMessage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface AppealMessageRepository extends JpaRepository<AppealMessage, Long> {

    List<AppealMessage> findByAppealIdOrderByIdAsc(Long appealId);

    List<AppealMessage> findByAppealIdInOrderByIdAsc(Collection<Long> appealIds);
}
