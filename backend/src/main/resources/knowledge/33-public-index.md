# 公共知识索引（PUBLIC RAG / 投稿期刊目录）

全局一份 `scope=PUBLIC`，`tenantId=public`。不是一租户一份库。启动时 `PublicKnowledgeSeeder` 读取 `classpath:knowledge/*.md` 切块入 ES；运营可用 `POST /api/ops/knowledge/reindex` 或 `scripts/rebuild-public-knowledge.sh`。

## 期刊目录（GET /api/venues）

IEEE、ACM、Nature、Elsevier、ACL、EMNLP、NAACL、NeurIPS、Springer、ICML、ICLR、AAAI、COLM、OSDI、SOSP、NSDI、ASPLOS。
与 `VenueCatalog` 及 02–08、14–17、23 专章标题对齐，便于 Citation / Figure / Style / Reviewer 检索命中。

## Agent 怎么用

任务字段 `review_task.target_venue`。选了刊名则公共检索词前置该刊；未指定则 `VenueQuery` 抽正文。
Style 不授予 AcademicSearch。客服/RAG 不可换用户模型。没有第四条 Workflow。云笺不落库。
模型配置：设置 → 模型配置（`/account?panel=models`）。论文 Agent 可填用户 OpenAI 兼容 Key；云笺与 RAG 仍走平台。没有开发者通用审校 API。详见 `34-how-to-models.md`。
