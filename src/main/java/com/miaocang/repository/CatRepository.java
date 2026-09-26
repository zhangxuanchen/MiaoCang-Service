package com.miaocang.repository;

import com.miaocang.entity.Cat;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CatRepository extends JpaRepository<Cat, Long> {
    List<Cat> findAllByOrderByOrderIndexAscIdAsc();
    List<Cat> findByUserIdOrderByOrderIndexAscIdAsc(Long userId);
    long countByUserId(Long userId);
    Optional<Cat> findByName(String name);

    /** 多用户重名校验：同一主人下猫名唯一 */
    Optional<Cat> findByUserIdAndName(Long userId, String name);
}
