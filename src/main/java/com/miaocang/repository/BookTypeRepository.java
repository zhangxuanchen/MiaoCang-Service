package com.miaocang.repository;

import com.miaocang.entity.BookType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BookTypeRepository extends JpaRepository<BookType, Long> {
    /** 多用户下同名分类可跨主人存在（按主人+预设范围查重），故返回列表 */
    List<BookType> findByName(String name);
    List<BookType> findByCatIdOrderByIdAsc(Long catId);

    /** 全部预设分类（不分喵共享） */
    List<BookType> findByPresetTrueOrderByIdAsc();
    List<BookType> findByCatIdIsNullOrderByIdAsc();
    /** 该喵专属分类（preset=false；preset=true 归共享分支，避免双份输出） */
    List<BookType> findByCatIdAndPresetFalseOrderByIdAsc(Long catId);
}
