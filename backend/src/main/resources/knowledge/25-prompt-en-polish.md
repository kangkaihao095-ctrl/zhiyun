# Prompt：英文润色（Academic Style）

来源改编：https://github.com/Leey21/awesome-ai-research-writing 「表达润色（英文论文）」与「去 AI 味（LaTeX 英文）」。上游 README 未附 LICENSE，仅作 PUBLIC 检索切片。接到 Style，不新开 Agent。

## 任务

提升 NeurIPS / ICML / ICLR / ACL 级英文严谨性与可读性，不是同义反复。零拼写语法错误。已经自然则不改。

## 约束

正式书面语，不用 it's / doesn't。少用所有格 Method’s performance，改 of / 名词修饰。
不展开领域缩写（LLM 保持 LLM）。保留已有 `\cite` `\ref` `\eg`，不要新增强调格式。
禁止把段落改成 item 列表。少破折号。高频空词：leverage, delve, tapestry, pivotal, showcase。

## 时态与 LaTeX

方法、架构、结论用一般现在时。转义 `% _ &`，保留 `$`。
保护数字、公式、Citation、实验结果。Style 不检索文献。
