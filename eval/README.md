# Agent 评测集

自建样本，用于链路回归，**不是**线上 1 万片段 / Recall@5 88% 的 SLA。

登录后可跑：

- `GET /api/eval/rag`：50 条公共知识查询，算 `Recall@5`
- `GET /api/eval/agents`：50 条 Agent 任务目录（稿件是否齐全、按 Agent 计数）
- `GET /api/eval`：两项一起

个人主页也可以点「跑检索测评」。

## 文件

| 文件 | 条数 | 用途 |
|---|---|---|
| `queries.jsonl` | 50 | 公共 RAG：`relevant_files` + `must_contain`，命中 Top-5 即算召回 |
| `agent-tasks.jsonl` | 50 | 7 个论文 Agent + 客服 + Workflow / Harness 期望 |
| `papers/*.md` | 6 | 上传稿：幽灵 DOI、AI 套话、Evidence Gap、Figure/PDF、FULL_REVIEW 混合、含图含表两页短稿 |

## 论文稿期望（手工或走审校）

| 文件 | 主要测谁 | 期望 |
|---|---|---|
| `papers/01-citation-ghost.md` | Citation Integrity | `10.0000/ghost.doi` → `NOT_VERIFIED` |
| `papers/02-style-ai-writing.md` | Academic Style | Firstly / In conclusion；`300 dpi`、`6 pages` 不得改 |
| `papers/03-reviewer-evidence-gap.md` | Academic Reviewer | 无 ablation 的 outperforms → Evidence Gap |
| `papers/04-figure-pdf-issues.md` | Figure/PDF | Letter vs ACL A4、缺图、题注 |
| `papers/05-full-review-mix.md` | 全链 | `WAITING_ACCEPT`；正式稿 version 仍为 1 直到 Accept |
| `papers/07-eval-two-page-figure-table.md`（及同名 PDF） | 含图含表的两页短稿 / 全链 LLM-as-judge | Figure 1 模糊且缺题注；Table 1 实表 + 引用不存在的 Table 2；Letter vs ACL A4；真/假 DOI；Evidence Gap |

客服问套餐走 PUBLIC RAG `00-billing.md`，问余额走 `usage_query`。

## 云笺客服回归（CsEval）

`eval/cs-eval.json` 是**问题 + 标准答案要点**（不是逐字背诵）。`purpose=regression`，**不是**线上 SLA。

覆盖：花了多少钱、历史充值/订单、今天和昨天的订单（日期范围过滤）、审校任务、额度余额、本账户没有的 id、套餐怎么买。

自动跑：

- `mvn -q -Dtest=CsEvalTest,CustomerServiceTest,ReadOnlyToolsTest test`
- `cd frontend && npm test`（`tests/cs-eval.spec.ts`）
- 本机 8080 已用 local 重启后：`ZHIYUN_CS_LIVE_EVAL=1 mvn -q -Dtest=CsLiveEvalTest test`

