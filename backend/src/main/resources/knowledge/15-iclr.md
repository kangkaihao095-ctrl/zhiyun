# ICLR 会议投稿规范（LaTeX / PDF）

来源摘录：ICLR 作者指南与 MIT 技能 https://github.com/zechenzhangAGI/AI-research-SKILLs `templates/iclr2026`。页数以当年为准。

## 排版

主文常见 9 页，camera-ready 10 页；参考文献与附录不限页。10pt Times New Roman 风格，行距约 11pt。
`\usepackage[submission]{iclr2026_conference}`；终稿 `final`。

## 公式与字体（ICLR）

与模板一致，不要换展示字体或缩边距。数学用 amsmath。匿名投稿去掉身份。

## 关注点与实验

可复现声明、伦理声明可选但不计入页数。方法稿需要公开基准与对照；纯仿真环境须写清与真实任务差距。
LLM 若实质参与构思或写作，须在附录披露角色；仅语法润色通常不必披露。不披露可能导致 desk reject。
Reviewer：缺实验支撑的贡献陈述标 Evidence Gap。
