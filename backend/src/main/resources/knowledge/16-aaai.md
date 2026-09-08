# AAAI 会议投稿规范（LaTeX / PDF）

来源摘录：AAAI Author Kit；模板说明见 https://github.com/zechenzhangAGI/AI-research-SKILLs `templates/aaai2026`（MIT）。

## 排版

US Letter。主文常见 7 页，camera-ready 8 页，参考文献与附录另计。`letterpaper` + `aaai2026`。
初稿 `\usepackage[submission]{aaai2026}`。必须 PDF。

## 公式与字体（AAAI）

正文必须 Times Roman 或 Nimbus，**禁止 Computer Modern 或 Palatino 当正文字体**。无衬线用 Helvetica，等宽 Courier。
数学可用 Symbol / Lucida / Computer Modern，但要嵌入 Type-1，避免 Type-3。
`\usepackage{times,helvet,courier}` 是常见组合。

## 关注点与实验

强调可核验贡献、清晰假设、可复现实验。应用稿不能只有 toy 仿真。双盲期间不要放可识别仓库地址。
