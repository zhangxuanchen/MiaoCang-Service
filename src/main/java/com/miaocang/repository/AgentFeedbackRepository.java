package com.miaocang.repository;

import com.miaocang.entity.AgentFeedback;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AgentFeedbackRepository extends JpaRepository<AgentFeedback, Long> {

    List<AgentFeedback> findTop50ByOrderByCreatedAtDesc();

    List<AgentFeedback> findByConsumedFalseOrderByCreatedAtAsc();

    List<AgentFeedback> findByCatIdAndConsumedFalseOrderByCreatedAtAsc(Long catId);
}
