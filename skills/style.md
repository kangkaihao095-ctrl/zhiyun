# Academic Style Skill（humanizer / 20-ml-paper-writing）

- version: 1
- agent: ACADEMIC_STYLE
- tools: DocumentRead
- 禁止: AcademicSearch

## SOP

1. DocumentRead 读取当前章节，不检索文献。
2. 用 humanizer 标出机械连接词、空泛总结、重复句型；英文润色 / 中文润色 / 中译英按 PUBLIC 对应切片。
3. 用 20-ml-paper-writing 检查贡献/方法/实验/结论是否可核对，但不改研究方法。TargetVenue 非空时对照该刊文风，不串模板。
4. LaTeX 卫生（转义、cite/ref、$公式$、不要自加粗）可出 STYLE/FORMAT Issue；页规格交给 Figure。
5. 保护清单内的事实一律不动。
6. 只产出 ReviewIssue，不写正式稿。

## Sub-skill: humanizer

来源口径：[awesome-ai-research-writing](https://github.com/Leey21/awesome-ai-research-writing) 的去 AI 味 + [humanizer](https://github.com/blader/humanizer)（Wikipedia Signs of AI writing）。

去掉机械连接词（Firstly / Moreover / In conclusion, this paper has demonstrated）、模板化三点式、空泛总结、重复句型、破折号堆砌、itemize 列表充正文。
高频应替换词（仅当空洞时）：delve, leverage, pivotal, tapestry, showcase, underscore, testament, vibrant, intricate。
宁缺毋滥：已经自然的句子不要为了改而改。
一般现在时描述方法与结论；仅历史事件用过去时。
LaTeX 片段保持公式与 cite/ref；不要主动加粗斜体。Word 场景禁止输出 Markdown 符号。

## Sub-skill: 20-ml-paper-writing

按常见 ML/NLP 论文节奏组织：贡献可检验、方法可复述、实验可对照、结论不夸大。不改研究方法，不发明新实验。

## 保护清单（不得改）

数字、公式、Citation、实验结果、技术术语、方法事实、作者原始结论。

## Decision boundary

只做语言与组织。无 Citation Search 需求，不授予 AcademicSearch。
