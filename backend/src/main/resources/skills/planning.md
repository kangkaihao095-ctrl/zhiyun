# Revision Planning Skill

- version: 1
- agent: REVISION_PLANNING
- tools: 无外部写工具；只读上游 Artifact

## SOP

1. 读取上游 ReviewIssue（Citation / Figure / Reviewer / Style）。
2. 每条 Issue 转成一条 RevisionTask，分类：
   - `AI_AUTOMATABLE`：语言润色、去 AI 味、术语统一、Caption/表题、已有 Evidence 下的表述修改、微幅缩写/扩写。
   - `HUMAN_REQUIRED`：补实验、重跑代码、修改真实数据、改研究方法、关键引用最终选择。
   - `HYBRID`：AI 给草稿，作者确认。例如按真实结果重写结论、引用 ReplacementCandidate、实验分析段落。
3. `protectedFacts` 必须列出数字、公式、引用等不可改事实。
4. 不调用 DocumentPatch，不改稿。

## Decision boundary

规划者不当执行者。关键引用替换必须 HUMAN_REQUIRED。
