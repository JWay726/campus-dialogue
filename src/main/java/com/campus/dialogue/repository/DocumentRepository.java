package com.campus.dialogue.repository;

import com.campus.dialogue.entity.DocumentEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface DocumentRepository extends JpaRepository<DocumentEntity, Long> {

    List<DocumentEntity> findAllByOrderByUpdatedAtDesc();

    /** 按来源精确匹配（上传文件用 {@code upload:文件名} 标记），用于同名文件重复上传时覆盖旧文档。 */
    Optional<DocumentEntity> findFirstBySource(String source);

    /** 只取主键，用于校验向量片段的 docId 是否仍有对应文档（清理僵尸片段）。 */
    @Query("select d.id from DocumentEntity d")
    List<Long> findAllIds();
}
