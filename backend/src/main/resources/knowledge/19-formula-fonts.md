# 公式字体与数学排版（Figure / Style）

来源：各刊模板；Ethan Perez / Steinhardt 数学写作口径收录于 MIT https://github.com/zechenzhangAGI/AI-research-SKILLs `references/writing-guide.md`。

## 跨刊共性

标量斜体 $x$，向量粗斜体 $\mathbf{x}$，矩阵粗体 $\mathbf{W}$，集合花体 $\mathcal{X}$。命名函数用罗马体 $\mathrm{softmax}$。
符号第一次出现必须定义。全文记号一致，不要同一量换三个名字。
定理前把假设写全。公式不要超栏；双栏拆行或缩小，不要改页边距。

## 分刊字体

- IEEE / ACL / ICML / ICLR / NeurIPS / COLM：正文 Times 族；数学可 CM，但须嵌 Type-1。
- AAAI：正文禁止 Computer Modern，必须 Times/Nimbus；数学可用 CM。
- ACM acmart：不要另换正文字体。
- Nature：图内 5–7pt 无衬线；主文公式少而必要。
- Elsevier / Springer：跟 elsarticle / sn-jnl，不要套 IEEE 双栏 Times。

## Style / Figure 边界

字号、栏宽、Type-3、未嵌字体 → Figure/PDF（程序能查的先查）。
记号混乱、公式当形容词堆砌 → Style 或 Reviewer。不要新开「LaTeX Agent」。
