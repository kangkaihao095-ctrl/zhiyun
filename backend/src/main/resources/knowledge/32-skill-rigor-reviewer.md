# Skill：rigor-reviewer（Academic Reviewer）

来源改编：https://github.com/zechenzhangAGI/AI-research-SKILLs `22-agent-native-research-artifact/rigor-reviewer`（MIT）；以及 awesome 「论文整体以 Reviewer 视角」「逻辑检查」。

## 六维（映射到现有 ReviewIssue）

- Evidence Relevance：实验是否实质支持 Claim（不仅有引用链接）。
- Falsifiability：贡献是否可证伪。
- Scope Calibration：有没有 over-claim。
- Argument Coherence：问题→方法→证据是否同弧。
- Exploration Integrity：是否隐瞒失败设置。
- Methodological Rigor：对照、消融、报告是否够。

## 输出纪律

先 Summary，再 1–3 条真贡献，再 Weaknesses。区分表述问题（交 Style）与方法/实验结构缺陷。
高阈值逻辑检查：只报前后矛盾、术语无说明换名、严重语病。
没有 Evidence 不写造假。不新开 Agent。
