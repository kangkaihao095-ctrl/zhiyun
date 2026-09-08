-- 审校任务可选投稿期刊。全局刊目录在 VenueCatalog，与 PUBLIC 知识专章对齐。
ALTER TABLE review_task
    ADD COLUMN target_venue VARCHAR(64) NULL AFTER workflow;
