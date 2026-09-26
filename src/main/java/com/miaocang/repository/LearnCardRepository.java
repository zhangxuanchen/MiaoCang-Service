package com.miaocang.repository;

import com.miaocang.entity.LearnCard;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

public interface LearnCardRepository extends JpaRepository<LearnCard, Long> {

    List<LearnCard> findByCatId(Long catId);

    /** 今日到期卡流：未休眠且 due<=今天，先到期先复习（调用方再截断每日上限） */
    List<LearnCard> findByCatIdAndDormantFalseAndDueLessThanEqualOrderByDueAscIdAsc(Long catId, LocalDate today);

    long countByCatId(Long catId);

    long countByCatIdAndDormantTrue(Long catId);

    /** 派生 delete 在异步线程无事务会炸（No EntityManager with actual transaction），改批量 delete 自带事务 */
    @Modifying
    @Transactional
    @Query("delete from LearnCard c where c.catId = :catId")
    void deleteByCatId(@Param("catId") Long catId);
}
