# ACL / *ACL 投稿规范（LaTeX / Word / PDF）

来源摘录：https://acl-org.github.io/ACLPUB/formatting.html ；https://github.com/acl-org/acl-style-files 。

## 必须用官方样式

只用 acl-style-files：LaTeX（`acl.sty`、`acl_latex.tex`、`acl_natbib.bst`）或 Word（`word/acl.docx`）。禁止改样式文件或套用 IEEE/NeurIPS 模板冒充 ACL。
Overleaf 提供官方模板。终稿与审稿稿都走同一套文件，审稿加 `review` 选项。

## PDF

只接受 PDF。必须嵌入全部字体（树图、符号、中日韩）。START 终稿上传会拒绝未嵌字体的文件。
纸张必须是 A4（21 cm × 29.7 cm），其他尺寸可能直接拒稿。
双栏。除非拉丁文和公式外，正文字体 Times Roman；没有则 Times New Roman 或 Computer Modern Roman。
标题 15pt 粗，作者 12pt 粗，小节标题 12pt 粗，正文 11pt，摘要/图注/参考文献 10pt，脚注 9pt。

## 页数

长文内容页常见 8 页，短文 4 页，均不含参考文献。图和表算进内容页。
录用终稿通常多给 1 个内容页以回应审稿。Limitations 节在参考文献之前，多为强制。附录与补充材料一般不计入内容页，但仍鼓励提供。

## LaTeX

审稿：`\documentclass[11pt]{article}` 并 `\usepackage[review]{acl}`（以当年 acl_latex.tex 为准）。
终稿去掉 review。强烈建议 pdfLaTeX。
元数据（OpenReview / Anthology 表单）必须是纯 Unicode，不要把 LaTeX 命令填进标题字段。

## Word

使用 `acl.docx` 内置样式（标题、摘要、图注、参考文献），不要手动改栏宽。

## 公式与字体（ACL）

除拉丁文和公式外，正文字体 Times Roman；没有则 Times New Roman 或 Computer Modern Roman。
公式可保留 Computer Modern math。必须嵌全部字体（含符号与 CJK）。不要改 `acl.sty`。

## 关注点与实验（ACL / EMNLP / NAACL）

关注语言现象、数据集、评测是否可复现、偏差与 Limitations。
仅合成数据或自造 benchmark 而无对照/人类评估时，优越性声明是 Evidence Gap。
仿真对话或 LLM 自说自话不能代替标准测试集或人工评测。Limitations 在参考文献前，多为强制。
