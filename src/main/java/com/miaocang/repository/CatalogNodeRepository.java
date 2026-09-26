package com.miaocang.repository;

import com.miaocang.entity.CatalogNode;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CatalogNodeRepository extends JpaRepository<CatalogNode, Long> {
    List<CatalogNode> findByBookIdOrderByOrderIndexAscIdAsc(Long bookId);
    List<CatalogNode> findByBookIdAndParentIdOrderByOrderIndexAscIdAsc(Long bookId, Long parentId);
    Optional<CatalogNode> findByBookIdAndName(Long bookId, String name);
    Optional<CatalogNode> findByBookIdAndParentIdAndName(Long bookId, Long parentId, String name);
    long countByParentId(Long parentId);
}
