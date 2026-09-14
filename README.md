# 智云 Zhiyun

多租户论文审校 SaaS。上传 PDF / DOCX / Markdown / TXT / TeX，按 Workflow 跑多 Agent 审校；修改以候选稿给出，作者 Accept / Reject 后才写入正式稿。独立智能客服「云笺」回答套餐、额度、订单和任务进度。

核心抽象：`Prompt → Skill → Agent → Workflow`。LiteFlow 编排节点；Harness 负责租约、fencing token、checkpoint、Tool 白名单与 Structured Output 校验。Agent 之间传递 `ReviewIssue / Evidence / RevisionTask / RevisionPatch / VerificationResult` 结构化对象。

## 功能

- **三条 Workflow**（同一组 LiteFlow 节点的不同组合）
  - `CITATION_ONLY`：Citation Integrity
  - `QUICK_REVIEW`：Citation → Academic Style
  - `FULL_REVIEW`：Citation → Figure/PDF → Reviewer → Style → Planning → Execution → Verification；结束后进入 `WAITING_ACCEPT`
- **7 个论文 Agent**：Citation Integrity、Figure / PDF Quality、Academic Reviewer、Academic Style、Revision Planning、Revision Execution、Final Verification
- **云笺**：RAG + 只读 Java Tool + SSE；对话驻留前端内存，刷新即新会话
- **RAG**：公共投稿规范 / 写作指南（`scope=PUBLIC`）与论文私有 Chunk（`tenantId + manuscriptId + documentVersion`）分 Scope 检索
- **计费**：额度账户；`POST /api/orders/{id}/mock-pay` 模拟支付到账
- **多租户**：共享 MySQL / Elasticsearch；行 / 文档级 `tenantId` 隔离。身份取自 JWT（`sub=userId`, `tid=tenantId`）
- **观测台**：管理员壳 `/ops`，按时间窗看任务、Agent 瀑布与 Harness

## 技术栈

| 层 | 技术 |
|---|---|
| 后端 | Java 17、Spring Boot 3.3、Spring Security、Spring Data JPA、Flyway |
| 编排 / 消息 | LiteFlow 2.12、RabbitMQ |
| 存储 | MySQL 8、Redis 7、Elasticsearch 8.13（`dense_vector`，余弦相似度，维度 1024） |
| 文档 | Apache PDFBox、Apache POI |
| LLM | OpenAI 兼容 HTTP（默认阿里云百炼 / 硅基流动）；客服可选 Dify Chatflow |
| 文献检索 | Crossref `api.crossref.org` |
| 前端 | Vue 3、Vue Router、Vite 6 |

默认模型（`application.yml`，可用环境变量覆盖）：

| 用途 | 配置项 | 默认值 |
|---|---|---|
| 论文 7 Agent | `ZHIYUN_PAPER_MODEL` | `qwen3.8-flash` |
| 云笺 | `ZHIYUN_CS_MODEL` | `qwen3.7-flash` |
| Figure / PDF Vision | `ZHIYUN_VISION_MODEL` | `qwen3.8-flash` |
| Embedding | `ZHIYUN_EMBED_MODEL` | `qwen3.7-text-embedding`（`dims=1024`） |
| Rerank | `ZHIYUN_RERANK_MODEL` | `qwen3.7-text-rerank` |

各论文 Agent 由 Prompt、Skill、Context 与 ToolPolicy 配置。用户可在「设置 → 模型」为单个论文 Agent 填 OpenAI 兼容 Key；云笺与 Embedding / Rerank 使用平台配置。

## 架构

```
Vue 3 :5173
    │  /api 代理
    ▼
Spring Boot :8080
    ├─ JWT / tenantId 上下文
    ├─ LiteFlow Workflow → 7 Agent 节点
    ├─ Harness（lease / fencing / checkpoint / ToolPolicy）
    ├─ RAG（ES kNN + filter → Rerank Top-5）
    ├─ 云笺 SSE（Dify 或本地 Adapter）+ MCP 只读 Tool
    └─ RabbitMQ 审校队列 zhiyun.review.tasks

MySQL 3306   Redis 6379   RabbitMQ 5672   Elasticsearch 9200
观测台 /ops（ops 登录）
```

任务状态：`PENDING → RUNNING → WAITING_ACCEPT → DONE`，失败为 `FAILED`。`CITATION_ONLY` / `QUICK_REVIEW` 无候选稿时直接 `DONE`。额度不足返回 409。

额度：`ceil(tokens / 2000)` 点，最少 1 点，不超过该 Workflow 上限（Citation 3 / Quick 5 / Full 10），且不超过余额。同一 `taskId` 只结算一次。自备模型时记技能费：引用核验 1 / 快速审读 1 / 完整审校 2。

## 环境要求

- JDK **17**
- Maven 3.9+
- Node.js 18+
- Docker（MySQL、Redis、RabbitMQ、Elasticsearch）

本机端口：后端 `8080`，前端 `5173`。中间件：MySQL `3306`、Redis `6379`、RabbitMQ `5672`（管理台 `15672`）、Elasticsearch `9200`。观测台入口为管理员 `/ops`。

## 快速开始

### 1. 启动中间件

```bash
cd zhiyun
docker compose up -d
```

MySQL 库名 / 用户 / 密码均为 `zhiyun`。RabbitMQ 用户 / 密码均为 `zhiyun`。Elasticsearch 单节点、关闭 xpack 安全。`docker compose up -d` 启动 MySQL / Redis / RabbitMQ / Elasticsearch。

### 2. 配置模型（可选）

无 Key 且 `ZHIYUN_LLM_MODE=auto` 时走 **dry-run**（fixture / hash 伪向量）。

真实调用：复制 [`.env.example`](.env.example)，导出 Key 后再启动后端。百炼：

```bash
export DASH_SCOPE_API_KEY=sk-...
export ZHIYUN_LLM_MODE=auto
export ZHIYUN_LLM_PROVIDER=dashscope
export ZHIYUN_LLM_BASE_URL=https://dashscope.aliyuncs.com/compatible-mode/v1
```

或硅基流动：`SILICONFLOW_API_KEY` + `ZHIYUN_LLM_BASE_URL=https://api.siliconflow.cn/v1`。

云笺默认 `ZHIYUN_CS_RUNTIME=dify`。同时设置 `DIFY_API_URL`（例如 `https://api.dify.ai/v1`）和 `DIFY_API_KEY` 时走远程 Chatflow；否则走同契约本地 Adapter。额度、订单、任务由 Java 服务记录。

### 3. 启动后端

```bash
export JAVA_HOME=/usr/local/opt/openjdk@17   # 按本机 JDK 17 路径调整
export PATH="$JAVA_HOME/bin:$PATH"
export SPRING_PROFILES_ACTIVE=local
cd backend && mvn spring-boot:run
```

Flyway 在启动时建表，并写入演示账号与套餐。健康检查：

```bash
curl -s http://127.0.0.1:8080/actuator/health
```

应为 `{"status":"UP"}`。

### 4. 启动前端

```bash
cd frontend
npm install
npm run dev
```

浏览器打开 [http://127.0.0.1:5173](http://127.0.0.1:5173)。Vite 把 `/api` 代理到 `8080`。

## 演示账号

| 项目 | 值 |
|---|---|
| 邮箱 | `demo@zhiyun.dev` |
| 密码 | `demo123456` |
| 额度 | 10 |

登录页预填该账号；同一账号可从 `/ops/login` 进入观测台。注册新用户会新建租户，赠送 **3** 额度。

主路径：登录 → 建课题 / 上传稿件 → 选刊（可选）→ 选 Workflow 启动审校 → 查看 Artifact / 报告 → `FULL_REVIEW` 时 Accept 或 Reject → 导出 → 订单页 mock 充值 → 云笺问额度。

## 观测台

管理员登录 [`/ops/login`](http://127.0.0.1:5173/ops/login)，看板 [`/ops`](http://127.0.0.1:5173/ops)。前端 `5173` 起来后，演示账号可进入。

数据按时间窗聚合 MySQL 的 `review_task`、`agent_span`、`task_lease`：窗口内的任务、Agent 节点与执行租约。记录带 `tenantId / taskId / agent / tool` 标签。观测台统计窗口内空召回次数，以及 Citation 的 `NOT_VERIFIED` 分布。

看板三层：

- **L1 总览**：窗口内创建 / 完成 / 积压、耗时分位、跨实验室活跃与失败
- **L2 一条任务**：点开任务看 Agent 瀑布，键是 `taskId + Agent`
- **L3 领域**：Agent / Tool 耗时与调用、LLM、引用核验、RAG 空召回、Harness

Harness 指标在 L3：lease 持有与过期、fencing 抬升与拒绝、checkpoint 跳过的节点。

时间窗从 15 分钟到至今；侧栏按实验室下钻。失败码按窗口计数。失败占比、fencing 拒绝、工具失败、空召回、积压达到窗口规则时，看板顶部标出对应条目。

## 配置

环境变量见 [`.env.example`](.env.example)。常用项：

| 变量 | 默认 | 说明 |
|---|---|---|
| `ZHIYUN_LLM_MODE` | `auto` | `auto`：有 Key 则 live，无 Key 则 dry-run；`live` / `dry-run` 强制 |
| `DASH_SCOPE_API_KEY` / `SILICONFLOW_API_KEY` | 空 | 模型与 Embedding / Rerank |
| `ZHIYUN_CS_RUNTIME` | `dify` | `dify` 或 `builtin` |
| `DIFY_API_URL` / `DIFY_API_KEY` | 空 | 远程云笺；都空则本地 Adapter |
| `ZHIYUN_MCP_TOKEN` | 本机回落 `dev-mcp-token` | MCP 请求头 `X-Zhiyun-Mcp-Token` |
| `JWT_SECRET` | 仓库占位（≥32 字符） | `prod` 使用仓库占位值或长度不足时进程退出 |
| `ZHIYUN_CORS_ORIGINS` | `http://127.0.0.1:5173,http://localhost:5173` | 逗号分隔的 Origin 列表 |
| `MYSQL_URL` / `MYSQL_USER` / `MYSQL_PASSWORD` | 本机 3306 / `zhiyun` | 数据源 |
| `ES_URL` | `http://127.0.0.1:9200` | 向量索引 `zhiyun_chunks` |

`application-local.yml` 已列入 `.gitignore`，用于本机密钥。生产环境的 `JWT_SECRET`、MCP token 与 CORS Origin 按部署填写。

## 目录结构

```
zhiyun/
├── backend/                 Spring Boot 应用
│   ├── src/main/java/com/zhiyun/
│   │   ├── agent/            SkillRegistry、运行时
│   │   ├── workflow/         LiteFlow 节点、编排、报告
│   │   ├── harness/          lease / fencing / checkpoint / ToolPolicy
│   │   ├── rag/             解析、切块、ES、公共知识
│   │   ├── cs/              云笺、MCP、只读 Tool
│   │   ├── llm/             LlmGateway、用户模型覆盖
│   │   ├── billing/          套餐、订单、mock 支付
│   │   └── web/             REST
│   ├── src/main/resources/
│   │   ├── knowledge/        公共 RAG 文稿（启动时入库）
│   │   ├── prompts/        Prompt 模板
│   │   ├── skills/          Skill SOP
│   │   ├── liteflow/        workflow.xml
│   │   └── db/migration/    Flyway
│   └── pom.xml
├── frontend/                Vue 3（论文 / 审校 / 订单 / 设置 + 云笺浮层）
├── prompts/                 与 classpath 同步的 Prompt
├── skills/                  与 classpath 同步的 Skill
├── scripts/rebuild-public-knowledge.sh
├── docker-compose.yml
└── .env.example
```

作者侧栏：`/` 论文，`/history` 审校，`/billing` 订单，`/account` 设置（`?panel=models` 模型）。稿件 `/manuscripts/:id`，任务 `/reviews/:id`（本次耗时、token、额度）。观测台 `/ops`（登录 `/ops/login`）为独立管理员壳。云笺为全局浮层。

## Agent 与 Tool

| Agent | 允许的 Tool |
|---|---|
| Citation Integrity | CitationParser、AcademicSearch、MetadataVerifier、WebSearch、ManuscriptRetrieval |
| Figure / PDF Quality | PDFParse、PDFRender、FigureExtract、FigureMetadata、Vision |
| Academic Reviewer | ManuscriptRetrieval、AcademicSearch |
| Academic Style | DocumentRead |
| Revision Planning | 只读上游 Artifact |
| Revision Execution | DocumentRead、DocumentPatch、DocxTool |
| Final Verification | ManuscriptRetrieval、AcademicSearch、MetadataVerifier、PDF/Figure、Diff |
| 云笺 | KnowledgeRetrieval、TaskStatus、UsageQuery、OrderQuery、CitationResult 等，全部只读 |

Citation：Java 调 Crossref 做 metadata 校验；LLM 判断 Evidence 是否支持 Claim；无可信 Evidence 返回 `NOT_VERIFIED`。Figure：DPI、页尺寸、编号等由程序检查，模糊 / 不可读 / 失真再交给 Vision。Execution 写入候选 `documentVersion`，作者 Accept 后进入正式稿。Final Verification 的 `basedOnExecutionSelfReport` 为 `false`。

RevisionTask 分类：`AI_AUTOMATABLE` / `HUMAN_REQUIRED` / `HYBRID`。补实验、改真实数据、改研究方法、关键引用最终选择标为人工。

## RAG

- 切分：按 Markdown / 论文章节与段落语义切
- 上传链：`Parse → Chunk → Embedding → Elasticsearch`
- Chunk 字段：`tenantId, researchProjectId, manuscriptId, documentVersion, section, chunkId, scope(PUBLIC\|PRIVATE)`
- 查询：kNN + `filter`，召回约 20，Rerank 后 Top-5
- 公共知识：`tenantId=public`，`scope=PUBLIC`；启动时 `PublicKnowledgeSeeder` 读取 `classpath:knowledge/*.md` 重建索引
- 无 Key / dry-run：确定性 hash 伪向量；无 ES 时用 `chunk_hash` + 文本匹配兜底（单测）

运营账号（`demo@zhiyun.dev` 或 `ZHIYUN_OPS_EMAILS`）可 `POST /api/ops/knowledge/reindex`，或：

```bash
./scripts/rebuild-public-knowledge.sh
```

期刊目录 `GET /api/venues`：IEEE、ACM、Nature、Elsevier、ACL、NeurIPS、Springer、ICML、ICLR、AAAI、COLM 及系统会等，与 knowledge 专章对齐。任务可选 `targetVenue`。

## API 摘要

前缀 `/api`。除 `/auth/**`、`/actuator/health`、`/actuator/prometheus` 外需要 `Authorization: Bearer <jwt>`。

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/auth/register` | 注册并创建租户 |
| POST | `/auth/login` | 返回 JWT |
| POST | `/auth/ops/login` | 观测台登录，返回 ops JWT |
| GET | `/workflows` | 三条 Workflow、额度上限 |
| GET | `/venues` | 期刊目录 |
| GET | `/me` | 当前用户与额度 |
| GET / POST | `/plans` `/orders` | 套餐与订单 |
| POST | `/orders/{id}/mock-pay` | 模拟支付。`{id}` 为业务单号 `ZY…`（兼容数字主键） |
| POST / GET | `/projects` `/manuscripts` | 课题与稿件（multipart 上传） |
| POST | `/manuscripts/{id}/reviews` | `{workflow, targetVenue?}` 启动审校 |
| GET | `/reviews/{taskId}` | 任务状态。对外号 `ZYT…` |
| GET | `/reviews/{taskId}/artifacts` `/report` `/diff` | Artifact、Markdown 报告、正式稿 vs 候选稿 |
| POST | `/reviews/{taskId}/accept` `/reject` `/cancel` | 采纳 / 拒绝 / 取消 |
| POST | `/cs/chat` | 云笺 SSE。`event: token` 增量，`event: done` 结束 |
| GET | `/ops/observability` | 观测台窗口聚合。须 ops JWT |

MCP：`/api/mcp`，请求头 `X-Zhiyun-Mcp-Token`，绑定会话 `tenantId / userId`，只读。

他租户资源返回 **404**（稿件、任务、报告、订单）。

## 测试

```bash
# 后端：H2 + dry-run
cd backend && mvn test

# 前端
cd frontend && npm test
```

## Harness

- Lease TTL 60s，每 20s 续期；过期后消息可重投
- 授予租约时 `fencing_token` 递增；旧 token 写入拒绝
- Checkpoint 粒度 = Agent 节点；重跑跳过已完成节点
- Artifact 唯一键 `(task_id, agent, artifact_type)`

## 许可证

仓库内公共知识文稿改编自各刊作者须知及 MIT / Apache 上游 Skill（见各 `knowledge/*.md` 文首来源行）。本仓库代码未附 LICENSE 文件。
