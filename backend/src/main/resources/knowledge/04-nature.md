# Nature / Nature Portfolio 投稿规范（Word / PDF / LaTeX / 图）

来源摘录：https://www.nature.com/nature/for-authors/formatting-guide ；Initial submission；Scientific Reports submission guidelines。这是 Nature 体系口径，页数和字数远严于 IEEE 会议。

## 初稿格式

强烈建议单一文件：把正文、图、图注做成一个 PDF 或 Word（Nature 初稿单文件可到约 30MB）。
图可插在对应段落或文末，图注与图同一页。PDF 请编行号；Word 投稿系统会自动加行号。
初稿阶段接受 PDF；LaTeX 源文件通常到接收后再交。Scientific Reports 修订稿不接受用 PDF 当正文，必须 Word 或 LaTeX。

## 文章结构（Nature 正刊）

顺序：题目、作者、单位、加粗首段（summary）、正文、主要参考文献、表、图注、Methods（含数据/代码可用性）、Methods 参考文献、致谢、资助、作者贡献、利益冲突、Extended Data 说明。
Articles 量级：正文大约 2500–3000 词、展示项（图+表）约 4–6 个、参考文献约 30–50，以当年 Formatting guide 为准。Methods 放在参考文献之后，不进主文。

## Word 与 LaTeX

终稿更偏好 Microsoft Word（去掉样式标签）。
TeX/LaTeX：接收后需能转到 Word 排版。全部文字（参考文献、表、图注、online methods）放进单个 `.tex`。可用 Springer Nature 模板 `sn-article.cls` / `sn-jnl.cls`。
修订稿：单栏、两端不对齐、页脚阿拉伯数字页码。

## 图

初稿分辨率只要审稿人能看清。终稿照片/半色调 ≥ 300 dpi，线图常见 ≥ 1200 dpi，组合图 ≥ 600 dpi。
栏宽：单栏约 89–90 mm，双栏约 180–183 mm，高度不超过约 170 mm。图内字 5–7 pt，无衬线（Arial/Helvetica）。
矢量：AI / EPS / PDF / SVG；位图：TIFF / PNG / JPEG；分层 PSD。颜色：线上 RGB，印刷可能转 CMYK。
用比例尺，不用放大倍数；需要时加误差线。多面板必须拼成一个文件再上传，不要 a/b/c 各传一张。

## 公式与字体（Nature）

主文极短，公式要少而必要。图内字 5–7 pt 无衬线（Arial/Helvetica）。不要把整页推导塞进主文，Methods 放参考文献后。

## 仿真还是必须实验（Nature）

Nature / Nature Communications / Scientific Reports 关注可独立核验的证据。
仅计算机仿真、没有实验或真实数据对照的稿，在生命/物化方向几乎不能当正刊主文；计算/理论稿必须把假设、验证与失败条件写清。
Reviewer：优越性若只有 toy simulation，标 Evidence Gap，不要写成造假。
