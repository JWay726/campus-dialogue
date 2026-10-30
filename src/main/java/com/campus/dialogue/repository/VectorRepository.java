package com.campus.dialogue.repository;

import com.campus.dialogue.entity.VectorEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface VectorRepository extends JpaRepository<VectorEntity, Long> {

    /** 按文档维度删除向量片段（用于文档编辑/重建时去重，避免重复片段）。 */
    void deleteByDocId(String docId);

    /** 按文档维度查询（保留用于调试/校验）。 */
    List<VectorEntity> findByDocId(String docId);
}
