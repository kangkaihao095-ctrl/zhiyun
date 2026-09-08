# Word / LaTeX / PDF 通用投稿格式

来源摘录：各出版社相机就绪惯例。具体会议以 IEEE / ACM / ACL / NeurIPS / Elsevier / Springer / Nature 专章为准，本节只给跨平台共性，禁止用本节覆盖专章冲突项。

## 终稿几乎都是 PDF

多数会议和期刊最终只收 PDF。Word 与 LaTeX 是源格式，用来生成符合模板的 PDF。
PDF 必须嵌入全部字体。ACL START、IEEE PDF eXpress、ACM TAPS 都会拒未嵌字体或改边距的文件。

## LaTeX

使用官方文档类，不要改 `\oddsidemargin` / `\textwidth` 硬挤页数。
参考文献用官方 bst / biblatex 样式，避免手写 `\bibitem` 充数。
图片优先 PDF/EPS 矢量；位图建议 ≥300 dpi（线稿更高）。
一个工程一个 `\documentclass` 主文件。

## Word

使用官方 .docx 样式，不要手动拉栏宽冒充双栏终稿。
删除模板占位说明文字。
IEEE 仍提供 Word 会议模板；ACM 旧 Word 模板已停用、走 TAPS；ACL 提供 `acl.docx`；NeurIPS 2026 主赛道 Word 模板已停用，只收 LaTeX 生成的 PDF。

## 纸张不要混用

ACL：A4 双栏。NeurIPS：US Letter 单栏。IEEE：随会议 CFP（Letter 或 A4）。Nature / Elsevier：按期刊 Guide for Authors。
客服若被问「论文是不是都用 A4」，必须先问目标会议/期刊。

## LaTeX 格式审查（接到 Figure / Style，不新开 Agent）

来源改编：https://github.com/Leey21/awesome-ai-research-writing 与 https://github.com/Leey21/arxiv-translator （MIT）。
- 只用官方 `\documentclass` / 样式，不要改 `\oddsidemargin` / `\textwidth`。
- 特殊字符转义：`%` `_` `&`；保留 `$` 公式与 `\cite` / `\ref` / `\label`。
- 不要自己加 `\textbf` / itemize 充正文。
- 编译：`pdflatex` → `bibtex` → `pdflatex` × 2，或 latexmk。引用显示 `?` 先修第一个 `!` 错误，或内联 `.bbl`。
- 中文稿：XeLaTeX / LuaLaTeX；不要同时开 `fontenc`+`inputenc` 与 Unicode 引擎。
- Type-3 位图字体：ICML / AAAI / IEEE eXpress 常拒。`pdffonts` 检查。
- AAAI 正文必须 Times/Nimbus，禁止 Computer Modern 当正文字体（数学可用 CM）。
