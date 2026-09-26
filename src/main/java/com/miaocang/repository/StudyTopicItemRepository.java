package com.miaocang.repository;

import com.miaocang.entity.StudyTopicItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface StudyTopicItemRepository extends JpaRepository<StudyTopicItem, Long> {

    List<StudyTopicItem> findByTopicIdOrderByAddedAtDescIdDesc(Long topicId);

    boolean existsByTopicIdAndItemTypeAndItemKey(Long topicId, String itemType, String itemKey);

    /** 批量 delete 自带事务（派生 delete 在异步线程会炸 No EntityManager） */
    @Modifying
    @Transactional
    @Query("delete from StudyTopicItem i where i.topicId = :topicId")
    void deleteByTopicId(@Param("topicId") Long topicId);

    @Modifying
    @Transactional
    @Query("delete from StudyTopicItem i where i.topicId = :topicId and i.itemType = :itemType and i.itemKey = :itemKey")
    void deleteOne(@Param("topicId") Long topicId, @Param("itemType") String itemType, @Param("itemKey") String itemKey);
}
