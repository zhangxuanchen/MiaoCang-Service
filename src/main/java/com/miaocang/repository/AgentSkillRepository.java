package com.miaocang.repository;

import com.miaocang.entity.AgentSkill;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AgentSkillRepository extends JpaRepository<AgentSkill, Long> {

    Optional<AgentSkill> findByCode(String code);

    /** 同猫同名可能有多条（老库迁移与播种撞名），返回列表由调用方取第一条 */
    List<AgentSkill> findByCatIdAndName(Long catId, String name);

    List<AgentSkill> findAllByOrderByKindAscIdAsc();

    List<AgentSkill> findBySourceOrderByUpdatedAtDesc(String source);

    List<AgentSkill> findByCatIdOrderByKindAscIdAsc(Long catId);

    List<AgentSkill> findByCatIdAndEnabledTrueOrderByKindAscIdAsc(Long catId);

    List<AgentSkill> findByCatIdIsNullOrderByIdAsc();
}
