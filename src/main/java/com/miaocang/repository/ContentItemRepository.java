package com.miaocang.repository;

import com.miaocang.entity.ContentItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ContentItemRepository extends JpaRepository<ContentItem, Long> {

    List<ContentItem> findByStatusOrderByCreatedAtDesc(String status);

    List<ContentItem> findByBookIdOrderByCreatedAtDesc(Long bookId);

    /** 知识图谱内容叶子：一批书下的全部条目 */
    List<ContentItem> findByBookIdIn(List<Long> bookIds);

    List<ContentItem> findByCatalogNodeIdOrderByCreatedAtDesc(Long catalogNodeId);

    /** 已落盘到书库文件树的条目 */
    List<ContentItem> findByLibraryPathIsNotNull();

    List<ContentItem> findByLibraryPathIsNotNullAndStatus(String status);

    Optional<ContentItem> findByLibraryPath(String libraryPath);

    /** 关联候选：最近的其它内容 */
    List<ContentItem> findTop20ByOrderByIdDesc();

    long countByStatus(String status);

    long countByBookId(Long bookId);

    long countByCatalogNodeId(Long catalogNodeId);

    /** 猫视角：某喵名下的内容（预设分类共享后内容直接挂归属喵） */
    List<ContentItem> findByCatId(Long catId);

    List<ContentItem> findByCatIdIsNull();

    long countByCatId(Long catId);

    /** 客户端握手：某喵名下待裁决（收集箱 PENDING）条数 */
    long countByCatIdAndStatus(Long catId, String status);

    /** 用户视角：一批喵（某用户名下）的内容总数 */
    long countByCatIdIn(Collection<Long> catIds);

    /** 猫视角：某本书里该喵名下的内容数 */
    long countByBookIdAndCatId(Long bookId, Long catId);

    /** 回填：已归档但缺归属喵的条目（按 书→分类→猫 链补齐） */
    List<ContentItem> findByBookIdIsNotNullAndCatIdIsNull();

    /** 全文搜索：标题 / 正文 / 标签 */
    @Query("select c from ContentItem c where lower(c.title) like lower(concat('%', :q, '%')) " +
            "or lower(c.rawText) like lower(concat('%', :q, '%')) " +
            "or lower(concat(',', coalesce(c.matchedTags, ''), ',')) like lower(concat('%,', :q, ',%')) " +
            "order by c.createdAt desc")
    List<ContentItem> search(@Param("q") String q);
}
