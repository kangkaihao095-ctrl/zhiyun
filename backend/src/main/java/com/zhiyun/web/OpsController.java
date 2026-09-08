package com.zhiyun.web;

import com.zhiyun.rag.PublicKnowledgeOps;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@RestController
@RequestMapping("/api/ops/knowledge")
public class OpsController {
    private final PublicKnowledgeOps knowledgeOps;

    public OpsController(PublicKnowledgeOps knowledgeOps) {
        this.knowledgeOps = knowledgeOps;
    }

    @GetMapping
    public Map<String, Object> catalog() {
        return knowledgeOps.catalog();
    }

    @PostMapping
    public Map<String, Object> upload(@RequestParam("file") MultipartFile file) throws Exception {
        return knowledgeOps.upload(file);
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable long id) {
        return knowledgeOps.delete(id);
    }

    @PostMapping("/reindex")
    public Map<String, Object> reindex() {
        return knowledgeOps.reindex();
    }
}
