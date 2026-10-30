package com.campus.dialogue.repository;

import com.campus.dialogue.entity.DocumentEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DocumentRepository extends JpaRepository<DocumentEntity, Long> {

    List<DocumentEntity> findAllByOrderByUpdatedAtDesc();
}
