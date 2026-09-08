# 写作实验室口径（Style / Execution 检索）

来源改编自 https://github.com/Leey21/awesome-ai-research-writing （Prompt 模板库）以及 humanizer / 20-ml-paper-writing。本文件供 PUBLIC RAG 检索，不是把第三方仓库原样复制进模型。

## 去 AI 味

避免 Firstly / Moreover / In conclusion, this paper has demonstrated。
避免破折号（—）堆砌和 item 列表充当正文。
空洞高频词可替换：delve, leverage, pivotal, tapestry, showcase, underscore, testament, vibrant, intricate。
已经自然的句子不要改。

## 时态与格式

方法、架构、实验结论用一般现在时。仅历史事件用过去时。
LaTeX：转义 % _ &，保留 $ 公式与 \cite/\ref。不要自己加粗。
Word：输出纯文本，禁止 Markdown 符号。

## Caption

图题：名词短语 Title Case、无句号；不要 The figure shows。
表题：Comparison with / Ablation study on / Results on。

## 与 Agent 边界

文风 → Academic Style。逻辑矛盾 / Evidence Gap → Academic Reviewer。
Caption 对错 → Figure/PDF 或 Style。生成架构图不是审校主链职责。
英语润色 / 中文润色 / 中译英 / LaTeX 格式审查都接到 Style、Reviewer、Figure 的 Prompt 与本 PUBLIC 切片，不新增 Agent 名字。
