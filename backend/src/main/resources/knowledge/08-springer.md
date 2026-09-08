# Springer Nature 期刊 LaTeX / Word 投稿

来源摘录：https://www.springernature.com/gp/authors/campaigns/latex-author-support 。

## 统一模板

Springer、Nature Portfolio、BMC 期刊可用同一套 Springer Nature LaTeX 模板（`sn-jnl.cls` / `sn-article.cls`，2024 年底约 3.1）。Overleaf 有官方包。
模板走 content-first，几乎不排最终版式。具体字数、引用格式仍以目标期刊 Instructions for Authors 为准。

## 编译与打包

提交 Snapp：必须能用 pdflatex 编译，源文件打 zip。
提交 Editorial Manager：不要带子目录；转换失败会返回 error log。
`.tex .bib .bbl .bst .sty .cls` 标 Manuscript；图片标 Figure。不要标 Supplemental。
不要同时上传自己编译的 PDF 和源文件，以免审稿包出现两份正文。

## 参考文献

按期刊选择 .bst（如 sn-mathphys-num、sn-basic）。选错不会编译失败，但格式会错，编辑部可能退回。

## Word

许多 Springer 刊接受 Word 初稿。若期刊要求 LaTeX 生产，接收后再转。不要把 IEEE 双栏 Word 模板当 Springer 模板。

## 公式与字体（Springer）

sn-jnl / sn-article 走 content-first，几乎不排最终版式。公式用 amsmath；参考文献 .bst 必须与期刊一致。
不要把 IEEE Times 双栏或 ACL A4 套进 Springer 源文件。

## 仿真还是必须实验（Springer）

以目标刊 Instructions for Authors 为准。应用刊常要真实数据或用户/系统测量；理论刊接受证明加算例。
Reviewer 不得用 NeurIPS checklist 套 Springer 期刊。
