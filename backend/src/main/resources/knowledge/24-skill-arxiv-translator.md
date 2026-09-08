# Skill：arxiv-translator / 英译中与中译英（Style）

来源：https://github.com/Leey21/arxiv-translator （MIT）；prompt 改编自 https://github.com/Leey21/awesome-ai-research-writing （README 未附 LICENSE，仅内部检索）。

## 英译中（理解用，不是投稿润色）

删除 `\cite` `\ref` `\label` 以免干扰阅读。`\textbf{text}` 只译括号内。公式改成可读描述，不要留 LaTeX 语法。
直译、少润色，语序贴近原文，便于对照。

## 中译英（接到 Style，产出仍是 ReviewIssue / 候选稿）

逻辑严谨、常用词、少破折号、不要 itemize 充正文。方法与结论一般现在时。
LaTeX：转义 `% _ &`，保留 `$` 与 cite/ref，不要自己加粗。
Word：纯文本，禁止 Markdown。可附中文直译核对，但不把核对文字写进正式稿。

## 专有名词

Transformer、Softmax、数据集名、模型名保留英文。人名机构不译。
审校主链不下载 arXiv、不编译远端 PDF；本切片只约束译文质量。
