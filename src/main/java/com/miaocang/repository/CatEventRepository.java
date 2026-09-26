package com.miaocang.repository;

import com.miaocang.entity.CatEvent;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 喵事件表（cat_events）仓库：埋点写库走 save，聚合查询走 CatEventService 的原生 SQL。
 */
public interface CatEventRepository extends JpaRepository<CatEvent, String> {
}
