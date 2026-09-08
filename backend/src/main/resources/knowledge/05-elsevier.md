# Elsevier 投稿规范（elsarticle / CAS / Word / Editorial Manager）

来源摘录：https://www.elsevier.com/researcher/author/policies-and-guidelines/latex-instructions ；elsarticle 与 els-cas-templates。

## Your Paper Your Way

许多 Elsevier 期刊初稿不强制版式，参考文献风格只要全文一致即可。是否 YPYW 以该刊 Guide for Authors 首页为准。
接收后才按期刊参考样式与模板收口。

## LaTeX 模板

大多数期刊用 `elsarticle`（CTAN/TeX Live 自带）。参考文献样式三选一，必须与文档类选项一致：
- 编号：`elsarticle-num.bst`
- 著者-年份 Harvard：`elsarticle-harv.bst`（文档类加 `authoryear`）
- 编号且显示作者名：`elsarticle-num-names.bst`

更新工作流可用 CAS 模板：单栏 `cas-sc.cls`，双栏 `cas-dc.cls`。Guide for Authors 提到 graphical abstract / highlights / CAS 时再用 CAS，否则用 elsarticle。

## 提交 Editorial Manager

初稿多数接受 PDF。
交源文件时：不要使用子文件夹，图、bib、cls 必须与主 tex 同一层，否则 EM 找不到图。
PDF 允许时：自己编译的 PDF 标为 Manuscript，源文件打包标为 LaTeX source files。
PDF 受限时：`.tex .bbl .bst .sty .bib .cls` 标 Manuscript，图片标 Figure，表标 Table。不要把 LaTeX 标成 Supplemental。
`.bib` 必须标成 Manuscript，否则 EM 编译后引用变成问号。
Overleaf 即使用 TeX Live 2022 能出 PDF，也不代表没有 error；EM 遇错会停。提交前在本地无报错编译。

## Word

可直接交 Word。若用 EndNote/Mendeley 域代码，提交前必须去掉 field codes，否则期刊生产链会坏参考文献。

## CRC 刊

Procedia 等相机就绪刊会原样印刷作者 PDF。确认编辑允许后才使用 `ecrc.sty` + elsarticle。

## 公式与字体（Elsevier）

elsarticle 默认 Computer Modern 或期刊指定字体。CRC 刊按相机就绪模板，不要混用 IEEE Times 双栏。
公式编号全文一致；图形摘要 / highlights 以该刊 Guide for Authors 为准。

## 仿真还是必须实验（Elsevier）

以该刊 Aim & Scope 为准。工程/计算机刊常接受仿真加少量实验；临床、材料、实验物理方向通常必须实验数据。
Your Paper Your Way 只放松初稿版式，不放松证据标准。
