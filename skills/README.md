# Agent Skills

对照 `md/智云_MultiAgent项目.md`：Skill = 这类专业任务怎么做（可复用 SOP）。
运行时由 `SkillRegistry` 从 `classpath:skills/` 加载，版本 `1`。本目录与 classpath 保持同步。

| 文件 | Agent | 要点 |
|---|---|---|
| `citation.md` | Citation Integrity | 解析 → Crossref → Java metadata → Evidence；无证据则 NOT_VERIFIED |
| `figure-pdf.md` | Figure / PDF Quality | 程序检查优先，Vision 只处理模糊/不可读/失真 |
| `reviewer.md` | Academic Reviewer | 按 Claim 取 Chunk；无 Evidence 不武断说实验错 |
| `style.md` | Academic Style | humanizer + 20-ml-paper-writing；不授予 AcademicSearch |
| `planning.md` | Revision Planning | Issue → AI_AUTOMATABLE / HUMAN_REQUIRED / HYBRID |
| `execution.md` | Revision Execution | 只写候选稿 |
| `verification.md` | Final Verification | 不采信 Execution 自述 |
| `customer-service.md` | AI Customer Service | RAG 答规则，只读 Tool 答数字 |

Prompt 模板在 [`../prompts/`](../prompts/)，结构为 Role / Task / Inputs / Constraints / Output Schema / Self-check。

写作口径吸收了 [awesome-ai-research-writing](https://github.com/Leey21/awesome-ai-research-writing) 的去 AI 味、时态、Caption 与 Reviewer 视角，落到 Style / Reviewer / Figure / Execution，而不是另起一套写作 Agent。
