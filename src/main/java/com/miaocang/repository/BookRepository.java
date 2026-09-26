package com.miaocang.repository;

import com.miaocang.entity.Book;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BookRepository extends JpaRepository<Book, Long> {
    List<Book> findByTypeIdOrderByIdAsc(Long typeId);
    Optional<Book> findByTypeIdAndDefaultBookTrue(Long typeId);
    long countByTypeId(Long typeId);
}
