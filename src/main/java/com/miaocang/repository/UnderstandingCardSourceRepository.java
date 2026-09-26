package com.miaocang.repository;

import com.miaocang.entity.UnderstandingCardSource;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface UnderstandingCardSourceRepository extends JpaRepository<UnderstandingCardSource, Long> {

    List<UnderstandingCardSource> findByCatId(Long catId);

    /** 某篇文章贡献了哪些知识点（反查） */
    List<UnderstandingCardSource> findByCatIdAndContentId(Long catId, Long contentId);

    /** 派生 delete 在异步线程无事务会炸，统一批量 delete 自带事务（同 UnderstandingCardRepository） */
    @Modifying
    @Transactional
    @Query("delete from UnderstandingCardSource s where s.catId = :catId")
    void deleteByCatId(@Param("catId") Long catId);
}
