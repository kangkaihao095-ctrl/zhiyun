# 智云开发任务书

对照 [md/智云_MultiAgent项目.md](../md/智云_MultiAgent项目.md) 的面试口径。编码只对照本文件，不现场发明字段。

## 0. 范围与默认决策

- 做：论文 7 Agent + 三条 LiteFlow Workflow + RAG + Harness（lease / fencing / checkpoint）+ 多租户 + 套餐/订单/Quota + 客服 SSE + MCP 只读 Tool + Vue 3 主路径。
- 不做：微信/支付宝进件、一租户一库、把评估数字写成线上 SLA。
- 支付：`POST /api/orders/{id}/mock-pay` 模拟支付成功并到账额度。
- 模型：`zhiyun.llm.mode=auto` 有百炼 Key 就走 DashScope。论文 `qwen3.8-flash`、客服 `qwen3.7-flash`、Figure 看图 `qwen3.8-flash`（多模态 chat，关思考）、生图 `qwen-image-3.0`（原生 multimodal-generation）、检索 `qwen3.7-text-embedding`@1024 + `qwen3.7-text-rerank`。`dry-run` 用 fixture。
- Dify：客服默认 `zhiyun.cs.runtime=dify`。配了 `DIFY_API_URL` / `DIFY_API_KEY` 就流式反代远程 Dify Chatflow；否则走同契约本地 Adapter（模型仍是 `cs-model`）。MCP 契约不变，Java 仍是额度/订单/任务权威。`dry-run` 测试不走 Dify。

## 1. 任务状态机

```
PENDING → RUNNING → WAITING_ACCEPT → DONE
                 ↘ FAILED
WAITING_ACCEPT → DONE   (Accept：候选 documentVersion 晋升为正式稿)
WAITING_ACCEPT → DONE   (Reject：正式稿仍为原 version，候选保留可查)
```

- 启动审校：校验 Quota ≥ 1 → 写 `review_task` PENDING → 投递 RabbitMQ。额度不足返回 409，不建任务。
- 任务结束（成功或失败）按消耗结算：`ceil(tokens / 2000)` 点，最少 1 点，不超过该 Workflow 上限（Citation 3 / Quick 5 / Full 10），且不超过当前余额。同一 `taskId` 只结算一次。
- Worker 崩溃：lease 过期后消息可重投；从最近 checkpoint 续跑，不重复扣额。
- `CITATION_ONLY` / `QUICK_REVIEW` 无 Revision 时：跑完后直接 `DONE`（不进入 WAITING_ACCEPT）。
- `FULL_REVIEW` 产出候选版本后进入 `WAITING_ACCEPT`。

## 2. Artifact JSON Schema

公共信封（落 `artifact.payload`）：

```json
{
  "schemaVersion": 1,
  "agent": "CITATION_INTEGRITY",
  "producedAt": "ISO-8601",
  "fencingToken": 3
}
```

### 2.1 PaperCandidate

```json
{
  "doi": "10.xxxx/xxxxx",
  "title": "string",
  "authors": ["string"],
  "year": 2019,
  "venue": "string",
  "abstractText": "string|null"
}
```

### 2.2 Evidence

```json
{
  "evidenceId": "ev-...",
  "claim": "string",
  "source": "CROSSREF|WEB|MANUSCRIPT",
  "paper": "PaperCandidate|null",
  "excerpt": "string",
  "supportsClaim": true,
  "confidence": 0.0,
  "status": "VERIFIED|NOT_VERIFIED|CONFLICT"
}
```

### 2.3 ReviewIssue

```json
{
  "issueId": "iss-...",
  "severity": "LOW|MEDIUM|HIGH|CRITICAL",
  "category": "CITATION|FIGURE_PDF|REVIEW|STYLE|LOGIC|FORMAT",
  "section": "string",
  "location": { "startOffset": 0, "endOffset": 0, "anchor": "string" },
  "summary": "string",
  "detail": "string",
  "evidenceIds": ["ev-..."],
  "sourceAgent": "CITATION_INTEGRITY"
}
```

### 2.4 RevisionTask

```json
{
  "taskId": "rt-...",
  "issueId": "iss-...",
  "kind": "AI_AUTOMATABLE|HUMAN_REQUIRED|HYBRID",
  "instruction": "string",
  "protectedFacts": ["numbers", "formulas", "citations"]
}
```

### 2.5 RevisionPatch

```json
{
  "patchId": "rp-...",
  "revisionTaskId": "rt-...",
  "issueId": "iss-...",
  "location": { "startOffset": 0, "endOffset": 0, "anchor": "string" },
  "originalText": "string",
  "proposedText": "string",
  "reason": "string",
  "evidenceIds": ["ev-..."]
}
```

### 2.6 VerificationResult

```json
{
  "resultId": "vr-...",
  "issueId": "iss-...",
  "resolved": true,
  "stillHumanRequired": false,
  "newProblems": ["string"],
  "notes": "string",
  "basedOnExecutionSelfReport": false
}
```

`basedOnExecutionSelfReport` 必须为 `false`。Final Verification 不得把 Execution 自述当证据。

## 3. 核心表

所有业务表带 `tenant_id`。查询必须带当前登录上下文的 `tenant_id`，禁止请求体传入租户覆盖。

| 表 | 关键字段 |
|---|---|
| `tenant` | id, name, created_at |
| `app_user` | id, tenant_id, email, password_hash, display_name |
| `research_project` | id, tenant_id, name |
| `manuscript` | id, tenant_id, project_id, title, current_version |
| `document_version` | id, tenant_id, manuscript_id, version_no, status(`OFFICIAL\|CANDIDATE\|REJECTED`), storage_path, content_text, content_sha256 |
| `review_task` | id, tenant_id, manuscript_id, workflow(`FULL_REVIEW\|QUICK_REVIEW\|CITATION_ONLY`), target_venue（可选，目录刊名；空则正文抽 venue）, status, source_version, candidate_version, checkpoint_agent, fencing_token, idempotency_key |
| `task_lease` | task_id PK, tenant_id, owner, expire_at, fencing_token |
| `artifact` | id, tenant_id, task_id, agent, artifact_type, payload JSON, fencing_token, UNIQUE(task_id, agent, artifact_type) |
| `chunk_hash` | tenant_id, manuscript_id, version_no, chunk_id, content_sha256 |
| `plan` | id, code, name, quota_amount, price_cents, description |
| `app_order` | id（内部 Long PK）, order_no（对外业务单号，形如 `ZY`+时间+随机；API 的 `id` 即此号，兼容用数字主键打开旧单）, tenant_id, user_id, plan_id, status(`PENDING\|PAID\|CANCELLED`), amount_cents |
| `quota_account` | tenant_id, user_id, balance, version（乐观锁） |
| `quota_ledger` | id, tenant_id, user_id, delta, reason, ref_id |

## 4. API

前缀 `/api`。除 `/auth/**`、`/actuator/health`、`/actuator/prometheus` 外需 `Authorization: Bearer <jwt>`。

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/auth/register` | 注册并创建租户 |
| POST | `/auth/login` | 返回 JWT |
| GET | `/workflows` | 三条 Workflow 编排、上限点数与扣费说明 |
| GET | `/venues` | 平台期刊目录（与 knowledge 专章对齐，可 `q`） |
| GET | `/me` | 当前用户 + 额度 |
| GET | `/plans` | 套餐列表（可走 Redis 缓存） |
| POST | `/orders` | `{planId}` 创建待支付订单 |
| POST | `/orders/{id}/mock-pay` | 模拟支付，增加额度。`{id}` 为业务单号（兼容旧数字主键） |
| GET | `/orders` | 我的订单（`id` 为业务单号） |
| GET | `/orders/{id}` | 订单详情（只读，租户隔离；`{id}` 为业务单号，兼容旧数字主键） |
| POST | `/projects` | 创建研究项目 |
| GET | `/projects` | 项目列表 |
| POST | `/manuscripts` | multipart 上传 PDF/DOCX |
| GET | `/manuscripts` | 列表。可选 `projectId`（JWT 租户隔离）；空则本租户全部，他租户课题 404 |
| GET | `/manuscripts/{id}` | 详情 + 版本 |
| GET | `/manuscripts/{id}/versions/{versionNo}` | 版本预览（抽出正文） |
| GET | `/manuscripts/{id}/versions/{versionNo}/inspect` | PDF 页规格 / 图表程序检查摘要；非 PDF 不给页预览 |
| GET | `/manuscripts/{id}/versions/{versionNo}/file` | 打开或下载原件 |
| POST | `/manuscripts/{id}/reviews` | `{workflow, targetVenue?}` 启动审校。刊名须在 VenueCatalog，只信 JWT 租户 |
| GET | `/reviews` | 本租户审校列表（`q,page,size,status`） |
| GET | `/reviews/{taskId}` | 任务状态 + checkpoint |
| GET | `/reviews/{taskId}/trace` | 单任务 Agent Trace（状态 / 耗时 / token / checkpoint / fencing / 错误降级码）。本租户，他租户 404 |
| GET | `/ops/observability` | ops JWT（`ops=true`）全平台窗口观测：空召回、span P50/P95、积压、窗口告警、跨租户计数。C 端 JWT 403，评估金标不进此接口。别名 `GET /observability` 同鉴权 |
| GET | `/reviews/{taskId}/artifacts` | Artifact 列表 |
| GET | `/reviews/{taskId}/report` | 当前任务 Artifact 汇总 Markdown（JSON：`filename` + `markdown`） |
| POST | `/reviews/{taskId}/cancel` | 取消 PENDING/RUNNING；本租户；取消后 FAILED 并释放 lease。他租户 404 |
| POST | `/reviews/{taskId}/accept` | 晋升候选稿 |
| POST | `/reviews/{taskId}/reject` | 拒绝候选稿 |
| GET | `/reviews/{taskId}/diff` | 正式稿 vs 候选稿 |
| POST | `/cs/chat` | SSE 客服（body: `{messages:[{role,content}]}`，最近 5 轮由前端截取） |

JWT claims：`sub=userId`, `tid=tenantId`。服务端只信 token，不信 body 里的 tenantId。

## 5. LiteFlow 与 ToolPolicy

节点复用，三条链不同组合：

- `CITATION_ONLY`：`citation`
- `QUICK_REVIEW`：`citation → style`
- `FULL_REVIEW`：`citation → figurePdf → reviewer → style → planning → execution → verification`

| Agent | 允许的 Tool |
|---|---|
| Citation | CitationParser, AcademicSearch, MetadataVerifier, WebSearch, ManuscriptRetrieval |
| Figure/PDF | PDFParse, PDFRender, FigureExtract, FigureMetadata, Vision |
| Reviewer | ManuscriptRetrieval, AcademicSearch |
| Style | DocumentRead（**不授予 AcademicSearch**） |
| Planning | 无外部写工具，只读上游 Artifact |
| Execution | DocumentRead, DocumentPatch, DocxTool |
| Verification | ManuscriptRetrieval, AcademicSearch, MetadataVerifier, PDF/Figure, Diff |
| Customer Service | KnowledgeRetrieval, TaskStatus, TaskList, PaperLookup, ManuscriptList, ManuscriptGet, UsageQuery, LedgerQuery, OrderQuery, PlanList, InboxUnread, AccountProfile, ModelConfig, CitationResult（全部只读） |

Prompt / Skill 文件（`classpath:prompts/` 与 `classpath:skills/`，版本 `SkillRegistry.VERSION=1`）：

| Agent | Prompt | Skill SOP |
|---|---|---|
| Citation Integrity | Role / Task / Inputs / Constraints / Output Schema / Self-check | 解析 → Search → Metadata 校验 → Evidence → 存在性 vs Claim 支持度 |
| Figure / PDF | 同上 | 程序检查优先，Vision 只处理模糊/不可读/失真 |
| Academic Reviewer | 同上 | 按 Claim 取 Chunk；无 Evidence 不武断 |
| Academic Style | 同上 | `humanizer` + `20-ml-paper-writing`；不授予 AcademicSearch |
| Revision Planning | 同上 | Issue → AI_AUTOMATABLE / HUMAN_REQUIRED / HYBRID |
| Revision Execution | 同上 | 只写候选 documentVersion |
| Final Verification | 同上 | `basedOnExecutionSelfReport=false` |
| Customer Service | 同上（自然语言输出） | RAG 答规则，Tool 答数字 |

仓库根目录 `skills/` 与 `prompts/` 与 classpath 同步，便于面试对照。

## 6. RAG

- 切分：按 Markdown/论文章节标题与段落语义切，不设死 size/overlap。
- Embedding：百炼 `qwen3.7-text-embedding`，请求 `dimensions=1024`（官方 256–2560 可配）；dry-run / 无 Key 用确定性 hash 伪向量。失败回退 hash，不让启动崩溃。
- Rerank：默认 `qwen3.7-text-rerank`（与 Embedding 同供应商，原生 text-rerank）；无 Key 时保持召回原序截断到 Top-5。
- ES index `zhiyun_chunks`：`tenantId, researchProjectId, manuscriptId, documentVersion, section, chunkId, scope(PUBLIC|PRIVATE), content, embedding dense_vector dims=1024 cosine`。已有索引维度不一致时删除重建。
- 查询：kNN + filter（tenant + manuscript + version + 可选 section）；召回 20，Rerank 后 Top-5。
- dry-run 无 ES 时：MySQL `chunk_hash` + 内存/简单文本匹配兜底，保证单测可跑。
- 公共知识 `tenantId=public`，`scope=PUBLIC`。私有检索必须带当前 tenant + manuscript + version。
- 期刊目录：`VenueCatalog`（IEEE/ACM/ACL/…/系统会），与 `knowledge/02–08、14–17、23` 对齐。任务 `target_venue` 优先于 `VenueQuery` 正文抽取，拼进 Citation/Figure/Style/Reviewer 的 PUBLIC 检索词。
- 知识来源与重建：见 README「公共知识来源与重建索引」。启动 Seeder 或 `POST /api/ops/knowledge/reindex`。

## 7. Harness

- Lease TTL 60s，每 20s 续期；`owner=hostname:pid:uuid`。
- 授予 lease 时 `fencing_token += 1`，写入与状态流转带 token，CAS：`WHERE fencing_token <= :token` 且更新后 token 以库为准比较，旧 token 写入拒绝。
- Checkpoint 粒度 = Agent 节点。`review_task.checkpoint_agent` 记录已完成的最后一个节点。重投时 `AgentTraceService.skip` 记 checkpoint 跳过。
- Trace：`GET /reviews/{id}/trace` 每节点含 status / durationMs / tokens / fencingToken / checkpoint / skipped / skillVersion / promptVersion / errorCode。
- 观测台：`GET /api/ops/observability` 需 ops JWT，聚合全平台 `review_task` / `agent_span` / `task_lease` 时间窗（空召回、P50/P95、积压、窗口告警、跨租户）。C 端 JWT 403。交付面是 `/ops`，不是 Prometheus / Grafana SLA 大屏，评估数字不当 SLO。
- `/actuator/prometheus` 仍可放行（进程内 Micrometer）；compose **不起** Prometheus。可选 `prometheus.yml` 仅供手动 scrape，不是观测台。
- Structured Output：Schema → Permission(ToolPolicy) → Business。失败重试 **1** 次，仍失败则任务 FAILED。
- 幂等：`artifact` 唯一键 `(task_id, agent, artifact_type)`；重跑同一节点不插入第二份。
- 故障注入：`zhiyun.fault.kill-after-agent` / `illegal-output-agent` / `timeout-agent`。

## 8. 客服 / MCP / SSE

MCP tools（内部 Token `X-Zhiyun-Mcp-Token`，绑定会话 tenant/user，拒绝写；与 `ToolPolicy` 云笺白名单一致）：

- `knowledge_retrieval`：公共 FAQ / 套餐规则
- `task_status`：`{taskId|manuscriptId}` → 状态、checkpoint、workflow
- `task_list`：我的审校任务
- `paper_lookup` / `manuscript_list` / `manuscript_get`：稿件只读
- `usage_query`：当前用户可见额度
- `ledger_query`：额度流水；`from`/`to` 由 Java 按 Asia/Shanghai 日历日过滤
- `order_query`：`{orderId?, from?, to?, status?, limit?}` → 参数化过滤；有日期时返回 range + 命中列表
- `plan_list` / `inbox_unread` / `account_profile` / `model_config`：套餐目录、未读站内信、账户与模型配置（无完整 Key）
- `citation_result`：`{taskId}` → Citation Artifact 摘要

SSE 事件：`event: token` data 为增量文本；`event: done` 结束。前端内存数组存 `{user, assistant}`，刷新即新会话，不写 localStorage。

## 9. 本地环境

```
SILICONFLOW_API_KEY=         # 有 Key 且 mode=auto/live 才打硅基流动
ZHIYUN_LLM_MODE=auto         # auto / live / dry-run
ZHIYUN_CS_RUNTIME=dify       # 或 builtin
DIFY_API_URL=                # 例如 https://api.dify.ai/v1 或 http://localhost/v1
DIFY_API_KEY=
ZHIYUN_MCP_TOKEN=dev-mcp-token
JWT_SECRET=change-me-in-prod-please-32chars
```

依赖：`docker compose up -d` 启动 MySQL 8、Redis、RabbitMQ、Elasticsearch 8。可观测交付面是 `/ops`。不要起 Prometheus / Grafana。可选 `prometheus.yml` 可手动 scrape `8080/actuator/prometheus`，不是观测台。

## 10. 用户模型覆盖 + 技能费

- 论文 7 Agent 默认仍走平台 `paper-model`（千问）。用户可在「模型」页按 Agent 选择「使用平台默认」或「接通我的 API Key」（OpenAI 兼容 / 百炼 compatible-mode：base URL、模型 ID、Key）。
- 运行对应 Agent 时 `AgentModelRouter` 按 `userId + agent` 覆盖，`LlmGateway` 用用户端点；token 计入用户云账单。
- 该次审校只要有自备 Agent，平台不再按 `ceil(tokens/2000)` 扣大额，改为记 `quota_ledger.reason=SKILL_FEE`：引用核验 1 / 快速审读 1 / 完整审校 2。前端文案「技能与提示词服务费」。
- 智能客服与 RAG（embedding / rerank）由平台提供，不可更换；任何用户覆盖都被忽略。
- 用户 Key 加密入库，接口只回 `sk-****` 后缀，不进日志与 artifact。
- `GET /api/me` 含 `avatarUrl`；`PUT /api/me` 改显示名/邮箱（JWT subject 仍是 userId）；列表 `GET /ledger|/orders|/manuscripts|/reviews` 支持 `q,page,size`，返回 `{items,total,page,size}`。
