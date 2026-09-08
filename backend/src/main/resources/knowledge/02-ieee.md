# IEEE 会议与期刊投稿规范（LaTeX / Word / PDF）

来源摘录：IEEE Template Selector https://template-selector.ieee.org/ ；IEEE conference templates；IEEEtran HOWTO；IEEE PDF eXpress 实践。客服与 Figure/PDF Agent 引用本节时必须标明这是 IEEE 口径，不能套用到 Nature 或 ACL。

## 接受的源格式

会议论文强烈建议使用官方会议模板。提供 Microsoft Word（US Letter 与 A4）以及 LaTeX IEEEtran。Overleaf 有 IEEE official 标签模板。
最终录用稿几乎一律要求 PDF。许多会议只接受 PDF 投稿；Word / LaTeX 用于生成符合模板的 PDF。

## LaTeX

会议稿文档类常见写法：`\documentclass[10pt,conference]{IEEEtran}`。
期刊/汇刊使用 journal 选项，不要把会议模板直接当汇刊模板。
以 IEEE Template Selector 上的当前包为准，部分刊物有专用模板。
双盲评审时作者栏使用匿名写法，例如 `\author{\IEEEauthorblockN{Anonymous Authors}}`。
推荐在双栏基式下排版，确保公式、表格、图能落入最终双栏。

## Word

使用官方 .doc 会议模板（US Letter 或 A4，2024 年仍在更新）。
提交前删除模板中的指导占位文字，残留指导文字可能导致无法进入 Xplore。
页脚未完成的版权行需按会议 camera-ready 说明处理。

## PDF 与版式

常见要求：双栏、单倍行距、10 点 Times Roman。
IEEE CAI 等会议明确纸张为 A4（210mm × 297mm）；部分会议用 US Letter，必须以该会 CFP 为准。
不要自行加页码，会议会统一插入。
字体必须是可嵌入的 PostScript 或 TrueType；禁止未授权或魔改字体。
图优先矢量（EPS/PDF），位图分辨率常见建议 150–300 dpi 量级，过低会在 Xplore 转换中发虚。

## 页数

随会议变化。例如 IEEE CAI：长文最多 6 页（含图表参考文献），可加购最多 2 页；摘要稿最多 2 页。Globecom 等常见标准 6 页。超页通常按页收费。

## PDF eXpress

Camera-ready 必须通过 IEEE PDF eXpress 检查或转换，得到 Xplore-compliant PDF。
LaTeX 用户可打包 DVI+EPS 让 eXpress 转 PDF；Word 用户可上传 .doc 让系统转换。
未通过 eXpress 的 PDF 可能不能进 IEEE Xplore。

## 公式与字体（IEEE）

正文字体常见 10pt Times Roman。公式与正文同族：不要把 Computer Modern 公式硬塞进 Times 栏面而不调字号。
推荐 `amsmath`，行间公式不要超栏宽；双栏用 `figure*` / 拆行。符号首次出现要定义。
IEEE 关注点：可复现方法、与既有系统/算法的定量对比、Xplore 可归档的 PDF（嵌字体）。

## 仿真还是必须实验（IEEE）

会议论文：仿真/数值实验在通信、信号处理、控制等方向通常可接受，但优越性声明仍要有对照与误差来源。
系统/硬件/应用向：仅仿真而没有原型、测量或真实痕迹，审稿人常标 Evidence Gap。
汇刊比会议更看重实验深度与统计报告。客服与 Reviewer 不得把「IEEE 一律只要仿真」说成规范。
