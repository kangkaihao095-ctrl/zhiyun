# Citation Integrity Skill

- version: 1
- agent: CITATION_INTEGRITY
- tools: CitationParser, AcademicSearch, MetadataVerifier, WebSearch, ManuscriptRetrieval

## SOP

1. 用 CitationParser 从正文与参考文献抽出 DOI / title / author / year / venue，不要靠模型记忆补全。
2. Runtime 在调用模型前对每条引用执行 AcademicSearchTool.lookupDoi（Java → Crossref），得到 PaperCandidate `{doi,title,authors,year,venue,abstractText}` 并注入 Prompt；模型不得自己搜或编 DOI。
3. Java MetadataVerifier 做确定性校验：DOI 形态、year、author、title 是否与源一致。模型不得改写这些字段。
4. 用 ManuscriptRetrieval 取出当前 Claim 所在章节，组装 Evidence。
5. 两个独立问题必须分开回答：
   - 论文是否存在（有无可信 PaperCandidate）
   - Evidence 是否真正支持当前 Claim（语义，由 LLM 判断）
6. 无可信 Evidence → `status=NOT_VERIFIED`，并产出 ReviewIssue；禁止编造 DOI / 年份 / venue。
7. RAG 命中只证明「稿件里出现过相关句」，不是存在性证明。

## Decision boundary

- 确定性 metadata：Java。
- Claim 支持度：LLM。
- 相关 ≠ 存在；相关 ≠ 支持。
