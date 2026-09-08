# Prompt：LaTeX 格式审查（Figure + Style）

来源改编：https://github.com/Leey21/awesome-ai-research-writing 与 arxiv-translator 编译错误表（MIT）。接到 Figure（页规格/字体/图）和 Style（源码纯净），不新开 Agent。

## Figure / PDF 先查

纸张：ACL=A4，NeurIPS/AAAI=Letter，IEEE 看 CFP。嵌字体、Type-1、栏宽、图 DPI、题注位置。
能程序判定的不要交给 Vision。

## Style 看源码卫生

官方文档类；禁止改 `\textwidth`。转义 `% _ &`。不要用 itemize 充当方法段。
`??` 引用先修首个 `!` 错误。中文稿不要混用 fontenc/inputenc 与 Xe/LuaLaTeX。

## 不要做的事

不在审校链编译远端 PDF，不发明第四条 Workflow。目标刊以任务 `targetVenue` 为准。
