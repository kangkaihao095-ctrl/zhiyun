package com.zhiyun.repo;

import com.zhiyun.domain.PublicKnowledgeDoc;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PublicKnowledgeDocRepo extends JpaRepository<PublicKnowledgeDoc, Long> {
    Optional<PublicKnowledgeDoc> findByFilename(String filename);
}
