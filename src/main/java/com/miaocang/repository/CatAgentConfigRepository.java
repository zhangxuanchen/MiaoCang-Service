package com.miaocang.repository;

import com.miaocang.entity.CatAgentConfig;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CatAgentConfigRepository extends JpaRepository<CatAgentConfig, Long> {
    Optional<CatAgentConfig> findByCatId(Long catId);
}
