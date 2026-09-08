# ICML 会议投稿规范（LaTeX / PDF）

来源摘录：https://icml.cc/ 作者须知；模板目录见 MIT 仓库 https://github.com/zechenzhangAGI/AI-research-SKILLs `20-ml-paper-writing/templates/icml2026`。口径按近年主会，页数以当年 CFP 为准。

## 排版

只收 PDF。主文最多 8 页（不含参考文献与附录），camera-ready 可再加 1 页。总文件常见上限约 10MB。附录必须与正文打进同一个 PDF。
10pt Times。只允许 Type-1 字体（`pdffonts` 检查）。不要改样式压缩行距。
双盲：初稿不要作者、致谢、基金号。图注在图下，表题在表上；图内不要再写标题。

## LaTeX

`\usepackage{icml2026}`；录用后 `[accepted]`。参考文献 `icml2026.bst`，多条引用按时间排序，尽量写页码。
Word 必须先转 PDF，不能交 .docx。

## 公式与字体（ICML）

正文 Times 10pt。公式随模板；避免 Type-3 位图字体（常见于旧 dvips 流程）。优先 pdflatex。

## 关注点与实验

Broader Impact 放文末、参考文献前，不计页数。要误差条、seed 次数、超参与算力。
只报单次运行的 SOTA、没有消融或对照，是 Evidence Gap。理论贡献要把假设写全。
