package com.miaocang.repository;

import com.miaocang.entity.TypeAttach;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface TypeAttachRepository extends JpaRepository<TypeAttach, Long> {

    boolean existsByTypeIdAndCatId(Long typeId, Long catId);

    List<TypeAttach> findByCatId(Long catId);

    /** 摘除挂接（异步线程安全：批量删除自带事务） */
    @Modifying
    @Transactional
    @Query("delete from TypeAttach a where a.typeId = :typeId and a.catId = :catId")
    int deleteByTypeIdAndCatId(Long typeId, Long catId);

    /** 删分类时清掉其全部挂接记录，防止孤儿行 */
    @Modifying
    @Transactional
    @Query("delete from TypeAttach a where a.typeId = :typeId")
    int deleteByTypeId(Long typeId);
}
