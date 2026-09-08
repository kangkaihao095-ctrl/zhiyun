# ACM 会议投稿规范（acmart / TAPS / Word）

来源摘录：https://www.acm.org/publications/proceedings-template ；Preparing Your Article with LaTeX；TAPS Best Practices。

## 模板

统一使用 Primary Article Template。LaTeX 类为 `acmart.cls`，会议录用最常见选项是 `sigconf`，SIGPLAN 会议用 `sigplan`。
不要再使用 2017 年旧 Word 模板；ACM 已停用该工作流。

## 审稿稿 vs 终稿

审稿：单栏、少样式。LaTeX：`\documentclass[manuscript,sigconf]{acmart}`，向会议系统交 PDF。
录用后：完成 ACM eRights，把邮件里的版权/会议命令写入源文件，去掉 `manuscript` 选项，补 CCS concepts 与 `\keywords`。
终稿源文件提交到 TAPS（The ACM Publishing System），由 TAPS 生成 PDF 与 HTML5，作者必须预览两份输出。

## LaTeX 约束

只允许 ACM 批准的宏包列表；禁止改边距、行距、字体或用 `\vspace` 硬调版心。
参考文献用 BibTeX，写全作者姓名，不要手写 `\bibitem` 应付。
工程里只有一个 `.tex` 含 `\documentclass`。文件名仅字母数字、短横、下划线。
`\includegraphics` 路径不要带 `./` 前缀。

## Word

按 TAPS Word 工作流使用现行 submission 模板（样式绑定段落，而不是手工排双栏）。
打包规则与 LaTeX 相同：ZIP 内必须有 `source/`，包含生成终稿所需全部资源；可选 `pdf/`、`supplements/`。
TAPS 上传 ZIP 一般限制 10MB，更大走 ACM 提供的 FTP。

## 检查清单

CCS 概念必须用 ACM CCS 工具生成完整 XML + `\ccsdesc` 块。
占位符 `\acmDOI`、`\acmConference` 必须换成版权邮件中的真实值。

## 公式与字体（ACM）

acmart 控制字体与栏宽，禁止改边距或用 `\vspace` 挤页。公式随 Libertine/Linux Libertine 体系，不要另换一套展示字体。
CCS concepts 与 keywords 是检索元数据，不是装饰。

## 仿真还是必须实验（ACM）

系统会议（SIGCOMM / SOSP 族）：实现与真实负载优先；纯仿真要说明为何不可测。
HCI（CHI）：用户研究或可核对的设计评估；不能只用示意图代替实验。
理论/算法：证明 + 适量实验即可，但声称系统增益必须有测量。
