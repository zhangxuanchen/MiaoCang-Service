package com.miaocang.repository;

import com.miaocang.entity.StudyTopic;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface StudyTopicRepository extends JpaRepository<StudyTopic, Long> {

    List<StudyTopic> findByCatIdOrderBySortAscIdAsc(Long catId);
}
