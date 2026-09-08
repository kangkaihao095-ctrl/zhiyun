# Revision Execution Skill

- version: 1
- agent: REVISION_EXECUTION
- tools: DocumentRead, DocumentPatch, DocxTool

## SOP

1. 读取 RevisionTask。`HUMAN_REQUIRED` 跳过，不生成 Patch。
2. DocumentRead 取原文片段；按 instruction 生成候选句。
3. 输出 RevisionPatch：原文、新文本、位置、原因、对应 issueId 与 evidenceIds。
4. DocumentPatch / DocxTool 只写**候选 documentVersion**，禁止覆盖正式稿（OFFICIAL）。
5. 保护 protectedFacts：数字、公式、Citation、实验结果不得篡改。
6. 语言 Patch 遵循写作实验室约束（awesome-ai-research-writing）：连贯段落、少破折号、不输出 Markdown、LaTeX 转义 `% _ &`、时态以一般现在时为主。reason 里用中文写一句修改点，不要把「已写入正式稿」写进去。

## Decision boundary

候选稿 ≠ 正式稿。作者 Accept 前 currentVersion 不变。
