package com.miaocang.repository;

import com.miaocang.entity.UnderstandingCard;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface UnderstandingCardRepository extends JpaRepository<UnderstandingCard, Long> {

    List<UnderstandingCard> findByCatIdOrderByIdAsc(Long catId);

    /** 出处内容条目反查卡片（书本/内容详情「喵喵提取」区用：比绕来源表的 no 匹配精确，不受编号撞号影响） */
    List<UnderstandingCard> findByCatIdAndContentIdOrderByIdAsc(Long catId, Long contentId);

    List<UnderstandingCard> findByCatIdAndBatchIdOrderByIdAsc(Long catId, String batchId);

    long countByCatId(Long catId);

    /** 派生 delete 在异步线程无事务会炸（No EntityManager with actual transaction），改批量 delete 自带事务 */
    @Modifying
    @Transactional
    @Query("delete from UnderstandingCard u where u.catId = :catId")
    void deleteByCatId(@Param("catId") Long catId);
}
