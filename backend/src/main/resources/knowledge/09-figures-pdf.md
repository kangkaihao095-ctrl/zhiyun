# 图、表与 PDF 质量通识（跨刊，供 Figure/PDF Agent）

确定性项由程序检查，不询问 Vision：页面尺寸、页数、嵌入图数量、像素宽高、估算 DPI、PDF 对象、题注与编号是否连续。
Vision 只处理：模糊、字不可读、拉伸失真、版式重叠、需要读图才懂的内容错误。

## 分辨率经验阈值

照片/半色调：≥ 300 dpi（Nature / Scientific Reports 终稿）。
线图：常要求 ≥ 600–1200 dpi。
IEEE Xplore：位图过低（明显低于 150 dpi）在转换后发虚。

## 尺寸

Nature 单栏约 90 mm、双栏约 180 mm。
IEEE/ACL 双栏图不要超出栏宽；跨栏图用 figure*。

## 文件格式

矢量优先：PDF / EPS / SVG / AI。
位图：TIFF（印刷）、PNG、高质量 JPEG。避免把整页扫描成 JPEG 再嵌 PDF。
多面板拼成一个文件。用比例尺而非 “×400”。

## PDF 规则

ACL：A4 + 嵌字体。IEEE camera-ready：PDF eXpress。
禁止提交每页都是整页位图的“假 PDF”（Word 打印质量差时常见）。
页码：IEEE 会议常要求作者不要自编页码；Nature/Scientific Reports 修订稿要页脚页码。以目标期刊为准。

## 图题 / 表题（Figure + Style）

来源改编：https://github.com/Leey21/awesome-ai-research-writing （上游 README 未附 LICENSE，仅内部检索）。
图题：名词短语 Title Case、无句号；不要 The figure shows / This diagram illustrates。
表题：Comparison with / Ablation study on / Results on。完整句子用 Sentence case 并加句号。
不要在图内堆长句；标签英文、短。色盲友好：Okabe-Ito / 线型区分，不要只靠红绿。
