package com.zhiyun.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(5)
public class PublicKnowledgeSeeder implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(PublicKnowledgeSeeder.class);

    private final PublicKnowledgeOps knowledgeOps;

    public PublicKnowledgeSeeder(PublicKnowledgeOps knowledgeOps) {
        this.knowledgeOps = knowledgeOps;
    }

    @Override
    public void run(ApplicationArguments args) {
        var result = knowledgeOps.rebuild();
        log.info("public RAG seeded: {} chunks", result.total());
    }
}
