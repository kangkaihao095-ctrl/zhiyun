# Skill：docx / Word 投稿（Execution + Style）

来源摘录：https://github.com/anthropics/skills `skills/docx`（document skills 为 source-available，不是 Apache 全文拷贝）。awesome-ai-research-writing Part II 引用本 skill。只抽论文相关口径。

## 论文场景

用期刊/会议官方 .docx 样式填标题、作者、摘要、正文，不要手拉栏宽冒充双栏终稿。
删除模板占位说明。修订建议用「原文 / 建议」对应 RevisionPatch，智云只写候选稿。

## 分刊

IEEE 仍提供 Word 会议模板。ACM 旧 Word 已停用、走 TAPS。ACL 提供 `acl.docx`。
NeurIPS 主赛道 Word 已停用。Elsevier 交 Word 前去掉 EndNote 域代码。

## 智云边界

DocxTool 只写候选 documentVersion。Style 不检索文献。
