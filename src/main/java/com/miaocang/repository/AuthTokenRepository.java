package com.miaocang.repository;

import com.miaocang.entity.AuthToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

public interface AuthTokenRepository extends JpaRepository<AuthToken, Long> {
    Optional<AuthToken> findByToken(String token);

    @Transactional
    @Modifying
    @Query("delete from AuthToken t where t.token = :token")
    void deleteByToken(String token);

    /** 清理过期令牌（登录/启动时顺手做） */
    @Transactional
    @Modifying
    @Query("delete from AuthToken t where t.expiresAt < :now")
    void deleteExpired(LocalDateTime now);
}
