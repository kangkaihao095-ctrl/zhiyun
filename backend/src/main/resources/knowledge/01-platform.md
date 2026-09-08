# 平台使用与 Workflow 说明

智云是多 Agent 论文审校 SaaS。上传 PDF / DOCX / MD / TXT，选择 Workflow，查看结构化 Artifact，FULL_REVIEW 产出候选稿后由作者 Accept 或 Reject。

## 三种 Workflow

FULL_REVIEW：Citation → Figure/PDF → Reviewer → Style → Planning → Execution → Verification。投稿前、大修前使用，成本最高，结束后进入 WAITING_ACCEPT。
QUICK_REVIEW：Citation → Style。日常改章节后看引用风险和语言。
CITATION_ONLY：只跑 Citation Integrity。批量核验 DOI 与 Claim 支持关系。

三种模式是同一组 LiteFlow 节点的不同组合，不是三套系统。

## 数据边界

论文、任务、订单、额度按登录上下文的 tenantId 隔离，不信任前端或模型传入的租户号。
公共投稿规范、套餐规则、写作指南属于 PUBLIC RAG。用户论文属于 PRIVATE RAG，检索必须带 manuscriptId 与 documentVersion。

## 如何配置模型

打开 **设置 → 模型配置**（`/account?panel=models`）。论文 7 个 Agent 可填用户自己的 OpenAI 兼容 Key（提供商 / Base URL / Key，保存即可）；平台收技能费。云笺和 RAG 仍走平台模型，不可换。Style 不授 AcademicSearch。没有给开发者的通用审校 API Key，也不走商务定制开放 API。额度在右上角，充值在旁边弹窗。
