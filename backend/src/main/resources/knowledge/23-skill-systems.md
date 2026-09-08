# Skill：systems-paper-writing（OSDI / SOSP / NSDI / ASPLOS）

来源：https://github.com/zechenzhangAGI/AI-research-SKILLs `systems-paper-writing`（MIT）。USENIX/ACM 系统会。

## 排版

常见双栏、10pt Times、US Letter。USENIX 投稿约 12 页（参考文献不限）；ACM ASPLOS 略紧。不要套 ACL A4 或 NeurIPS 单栏。

## 关注点

Novelty、Significance、可运行实现、真实负载端到端、可复现。
Levin & Redell：是实现经验还是思想实验？纯仿真、只有提案没有原型，系统会很难过。

## 评测结构

Setup → 端到端 → 微基准/消融 → 扩展性。每个贡献在评测里有对应实验。
Rebuttal 常见 500 词且不允许新实验（以当年 CFP 为准）。

## Reviewer / Figure

缺实现或真实负载 → Evidence Gap。页规格与 Times 10pt → Figure。文风仍走 Style。
