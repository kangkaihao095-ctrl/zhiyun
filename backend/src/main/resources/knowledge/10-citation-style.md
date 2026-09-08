# 引用、DOI 与学术写作（Citation / Style Agent）

## 引用真实性

RAG 命中稿件中的句子只说明「文中出现过」，不能证明文献存在。
存在性必须经 AcademicSearchTool → Crossref 等源 → Java 校验 DOI、title、author、year、venue。
无可信 PaperCandidate 则 NOT_VERIFIED，禁止模型编造 DOI。
「论文是否存在」和「是否支持当前 Claim」是两个问题。

## 常见格式

IEEE 会议：编号引用，IEEEtran / IEEEtran.bst。
ACL：natbib + acl_natbib.bst，著者年或按模板。
Elsevier：elsarticle-num 或 elsarticle-harv，与文档类选项一致。
Nature：主文参考文献数量紧，题名要写全；初稿参考文献表应含文章标题。

## 语言

避免 Firstly / Moreover / In conclusion, this paper has demonstrated 等套话。
禁止空洞三点式与空泛总结。润色时保护数字、公式、引用、实验结果、方法事实和作者原结论。
Style Agent 不授予 AcademicSearchTool。
