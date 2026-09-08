# NeurIPS 投稿规范（仅 LaTeX / PDF）

来源摘录：NeurIPS Main Track Handbook 2026；neurips_2026.sty 说明。口径按 2026 主赛道，其他年份页数可能不同。

## 格式

只接受单个 PDF，建议顺序：论文正文、参考文献、附录（证明与实验细节）、NeurIPS paper checklist。
代码与数据另传，不进这个 PDF。文件最大约 50MB。

## 页数

主文最多 9 页（含图和表）。参考文献、可选技术附录、强制 checklist 不算内容页。
录用 camera-ready 可再加 1 个内容页。超页或不遵守样式（缩边距、缩小字体）可能 desk reject。

## 模板

必须用当年官方 LaTeX：`neurips_2026.sty`。Microsoft Word 模板已停用，不能交 Word。
单栏、10pt、US Letter。可选参数：`final`（camera-ready）、`preprint`（arXiv 非匿名）、`nonatbib`。
双盲投稿不要加 final/preprint，去掉身份信息。checklist 不得删除。

## 与 IEEE/ACL 的差别

NeurIPS 是单栏 Letter，不是 ACL 的 A4 双栏，也不是 IEEE 双栏会议模板。客服不得把「A4 双栏 10pt」说成 NeurIPS 要求。

## 公式与字体（NeurIPS）

10pt、US Letter、单栏。公式随模板，不要缩边距挤页。checklist 在参考文献之后，不计内容页。

## 仿真还是必须实验（NeurIPS）

理论稿：假设写全、证明可核对即可，实验可作验证。
方法稿：公开基准 + 对照 + 消融 + 误差条；只报一次 seed 的「SOTA」是 Evidence Gap。
纯仿真环境（游戏、合成物理）可以，但必须说明与真实任务的差距。缺 checklist 可能 desk reject。
