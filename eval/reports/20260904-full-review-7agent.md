# 智云 7 Agent 输出质量评估报告

日期：2026-09-04（Asia/Shanghai）  
任务：`FULL_REVIEW` **taskId=5**，终态 **WAITING_ACCEPT**  
测试稿：`zhiyun/eval/papers/07-eval-two-page-figure-table.md`（上传实际跑的是同名 PDF）  
Judge 原始输出：`zhiyun/eval/reports/20260904-full-review-5-judge.json`  
产物 / Diff：`20260904-full-review-5-artifacts.json`、`20260904-full-review-5-diff.json`

---

## 1. 评估协议

**不另造量表。** 条目全部来自仓库已有 SOP / gold，覆盖率 = 命中条数 / 该 Agent 条目数（与 RAG `Recall@5` 的 hit/miss 口径同类）。

| 来源 | 章节 / 文件 | 用到的约定 |
|---|---|---|
| `md/智云_MultiAgent项目.md` | 「评估结果｜Recall@5 88% / Structured Output 96%」 | 自建集回归，不是线上 SLA；本次另跑仓库小样本 `GET /api/eval/rag` |
| 同上 | Citation / Figure / Reviewer / Style / Planning / Execution / Verification 各节 | 无 Evidence → `NOT_VERIFIED`；Figure 程序检查优先；Reviewer 克制；Execution 不覆盖正式稿；Verification 不采信自述 |
| `zhiyun/eval/README.md` + `agent-tasks.jsonl` | A01–A40 | 幽灵 DOI、Evidence Gap、Letter vs ACL A4、缺题注、Table 编号、`WAITING_ACCEPT`、正式稿 version=1 |
| `zhiyun/skills/*.md` + `prompts/*.md` | 各 Agent SOP | Planning：关键引用 → `HUMAN_REQUIRED`（**覆盖** A28 的 AI_OR_HYBRID 宽松项） |
| `zhiyun/DEV_TASK.md` §1–2、§5 | 状态机与 Artifact schema | `FULL_REVIEW` → 候选稿 → `WAITING_ACCEPT`；`basedOnExecutionSelfReport=false` |

**量表（仅此一种）**

- 每条 gold / SOP 项：`pass=1` 或 `0`
- Agent 分：`coveragePct`
- 另记 Structured Output：Java `validate()` 是否放行（本跑 7/7 通过；字段名相对 DEV_TASK 有漂移，见局限）

**Judge 提示摘要**：系统角色为「只按给定 gold 打 0/1，必须引用产物/原文短语，禁止新量表」。模型 `qwen3.8-flash`，`enable_thinking: false`，走百炼 compatible-mode `/chat/completions`。

**未做**：md 未要求 dry-run 对照，故只跑一次 live `FULL_REVIEW`。未重跑 50 条审校任务的 Structured Output 96% 大盘。

---

## 2. 测试设置

| 项 | 值 |
|---|---|
| 时间 | 2026-09-04 17:58:22–18:01:07 +08 |
| 论文 Agent 模型 | `qwen3.8-flash`（live） |
| Vision | `qwen3.8-flash`（Figure 节点） |
| LLM mode | `auto` / DashScope / `live=true` |
| Workflow | `FULL_REVIEW`：citation → figurePdf → reviewer → style → planning → execution → verification |
| 上传文件 | `07-eval-two-page-figure-table.pdf`（US Letter 612×792，2 页；矢量框图 + Table 1） |
| 稿件 ID | manuscriptId=2，sourceVersion=**1**，candidateVersion=**2** |
| 队列 | `zhiyun.review.tasks` 当时 1 个 consumer；`ReviewListener` 领取 task 5 |
| 小样本 RAG | `GET /api/eval/rag`：50/50，**Recall@5 = 100%**（仓库 50 条，不是 md 里约 1 万片段的 88%） |

植入缺陷（方便 7 Agent 都有信号）：真 DOI `10.18653/v1/N19-1423`、假 DOI `10.0000/ghost.doi`、过强 claim（99% / outperforms 无消融）、ACL 却用 Letter、Figure 1 模糊且缺题注、正文引用不存在的 Table 2、机械连接词 Firstly / In conclusion。

---

## 3. 各 Agent 产物摘要

完整 JSON 见 artifacts 文件。现场模型**未严格使用** DEV_TASK 字段名（Citation Issue 用 `message` 而非 `summary`；Evidence 扁平 `doi` 而非嵌套 `paper`），但 Java 校验未判非法。

### CITATION_INTEGRITY｜4 Evidence + 4 Issue

- `10.18653/v1/N19-1423` VERIFIED，claim_support=CONFLICT（存在但不支持「BERT 已解决引用幻觉」）
- `10.1145/3290605.3300233` VERIFIED，claim_support=NOT_VERIFIED（CHI 人机交互，不支持「无需 Java metadata」）
- `10.0000/ghost.doi` **NOT_VERIFIED**，未编替代 DOI
- `10.1145/example.2019` NOT_VERIFIED

### FIGURE_PDF｜4 Issue

- CRITICAL：612×792 Letter，ACL 应为 A4（标「确定性解析」）
- HIGH：Figure 3 / Figure 2 悬空
- MEDIUM：Figure 1 缺题注；PDF meta `figures=0`（矢量框不是 XObject）
- LOW：Table 2 被引用但只有 Table 1

### ACADEMIC_REVIEWER｜5 Issue

- Evidence Gap：99%、n=20、无消融、Table 1 数字对不上「+12 F1」
- 幽灵 DOI / 错误归因；未把 99% 写成数据造假
- LOW：图编号与 PDF meta figures=0

### ACADEMIC_STYLE｜4 Issue

- Firstly / Moreover / Secondly / Thirdly
- In conclusion / In summary 空泛收束
- 把「必须保护 300 dpi、6 pages」整句标成元指令泄露（数字本身未被改写）

### REVISION_PLANNING｜8 RevisionTask

- 幽灵 DOI、关键引用、ACL 重编译、缺图 → **HUMAN_REQUIRED**
- BERT 过声称 → HYBRID
- 题注、去 AI 套话 → **AI_AUTOMATABLE**

### REVISION_EXECUTION｜6 Patch + 候选 v2

- 正式稿仍为 v1；任务进入 `WAITING_ACCEPT`
- **违反 SOP**：Planning 已把幽灵 DOI 标 HUMAN_REQUIRED，Execution 仍改 Abstract，去掉 DOI 但**保留 12 F1 增益**
- 把「intended for ACL, prepared on Letter」改成「formatted for ACL using A4」——正文撒谎，PDF 仍是 Letter
- 为 Figure 1 补了题注（候选稿可见）

### FINAL_VERIFICATION｜6 条，全部 `basedOnExecutionSelfReport=false`

- 均为 `resolved=false`、`stillHumanRequired=true`
- 正确：页规格仍 612×792
- 可疑：仍写 Abstract 有幽灵 DOI、Caption is missing；Diff 显示这两处候选稿已改。更像核对了旧正文或部分章节

---

## 4. 分项分数

**千问 Judge 只完成 Citation、Figure。** 随后百炼返回 `Arrearage`（账户欠费/额度不足），其余 5 个 Agent 的 LLM Judge 未跑成。下表中「程序 SOP」是同一套 gold 的确定性核对，**不是**千问打分。

| Agent | 来源 | coveragePct | 要点 |
|---|---|---:|---|
| CITATION_INTEGRITY | 千问 Judge | **100** | 幽灵 DOI → NOT_VERIFIED；存在性与支持度分开 |
| FIGURE_PDF | 千问 Judge | **66.7** | A4/题注/Table 2 命中；模糊未做独立 Vision 语义判断（复述正文） |
| ACADEMIC_REVIEWER | 程序 SOP | 100 | Evidence Gap；无造假指控 |
| ACADEMIC_STYLE | 程序 SOP | 100 | Firstly / In conclusion；无 AcademicSearch |
| REVISION_PLANNING | 程序 SOP | 100 | 幽灵 DOI 与补实验为 HUMAN_REQUIRED |
| REVISION_EXECUTION | 程序 SOP | **80** | 正式稿未覆盖；**HUMAN_REQUIRED 仍被 Patch** |
| FINAL_VERIFICATION | 程序 SOP | 100 | 自述标志全 false；终态 WAITING_ACCEPT；候选稿核对不完整（见上） |

千问对 Figure 的未通过项：A22（模糊/印刷不可读的独立 Vision）、A25（pipeline 图质量的技术讨论不足）。

---

## 5. 图表相关是否被检出

| 植入问题 | 是否检出 | 谁检出 |
|---|---|---|
| US Letter vs ACL A4（612×792） | 是 | Figure CRITICAL；Verification 仍看到 Letter |
| Figure 1 缺题注 | 是（原文）；候选稿已被 Execution 补题注 | Figure MEDIUM |
| Figure 1 模糊 / 非矢量 | **弱** | 主要复述「正文说 blurry JPEG」；PDF 无 XObject，`figures=0`，Vision 未形成独立「印刷不可读」结论 |
| Table 1 实表存在 | 解析进正文 | Reviewer 用了 Table 1 数字 |
| 引用不存在的 Table 2 | 是 | Figure LOW |
| Figure 3 先于 Figure 2 | 是 | Figure HIGH；Planning HUMAN_REQUIRED |

---

## 6. 版本与 Human-in-the-loop

- 正式稿 `currentVersion` / `sourceVersion` = **1**，未被覆盖
- 候选 `candidateVersion` = **2**，任务 **WAITING_ACCEPT**（未在本评估中 Accept/Reject）
- Diff：候选改了 Abstract 引用句、BERT/Smith 表述、页规格**文字**、删除 Figure 3 句、补 Figure 1 题注；References 里幽灵 DOI 仍在

---

## 7. 局限与下一步

1. **Judge 中断**：Citation/Figure 之后百炼 `Arrearage`。补额度后用 `zhiyun/eval/retry_judge.py` 对后 5 个 Agent 重打，覆盖 `*-judge.json` 里的 fallback。
2. **Schema 漂移**：live 模型未输出 DEV_TASK 的 `summary` / `evidenceId` / `patchId`。建议收紧 Java Schema Validation，否则 96% Structured Output 口径偏松。
3. **Figure 视觉**：矢量框图不算 PDF Image XObject；`firstRaster` 虽可渲染首页，但产物仍以文本「blurry」为主。下一轮用带嵌入 JPEG 的 PDF 再测 Vision。
4. **Execution 越权**：HUMAN_REQUIRED 引用被自动改，且把 Letter 稿改成「已是 A4」。Planning→Execution 的 issueId 未对齐（`CITATION-CRITICAL-GHOST-DOI` vs `CITATION-01`），跳过逻辑失效。
5. **Verification 与 Diff 不一致**：应强制 PRIVATE RAG 限定候选 `documentVersion`。
6. RAG 100% 只说明当前 50 条 `queries.jsonl` 全中，**不能**写成 md 中 1 万片段 Recall@5 ≈ 88% 的复现。

---

## 8. 未完成项

- 5/7 Agent 的 **LLM-as-judge 未完成**（原因：百炼欠费 `Arrearage`），已用同口径程序 SOP 填空并在 JSON 中标注 `scoreSource=deterministic-sop-fallback`
- 未 Accept/Reject 候选稿
- 未跑 dry-run 对照（md 未要求）
