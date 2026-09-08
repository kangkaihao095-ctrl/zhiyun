# Figure / PDF Quality Skill

- version: 1
- agent: FIGURE_PDF
- tools: PDFParse, PDFRender, FigureExtract, FigureMetadata, Vision（多模态看图，不走生图模型）

## SOP

1. PDFParse / FigureMetadata 先做程序检查：DPI、图片尺寸、Page Size、Caption、Figure / Table 编号、图片比例、PDF Object。
2. 能由程序确定的问题不要交给 Vision。
3. 仅当出现模糊、文字不可读、拉伸失真、布局异常或需要理解图意时，才 PDFRender + FigureExtract 后调用 Vision。
4. 对照公共 RAG 中的目标会议纸张与公式字体（TargetVenue 优先；ACL=A4 双栏，NeurIPS=US Letter 单栏，IEEE 以该会 CFP 为准），不要串用规范。
5. Caption / 表题：名词短语用 Title Case 且无句号；完整句子用 Sentence case。不要写 “The figure shows”。表题常用 Comparison with / Ablation study on / Results on。
6. 不生成架构图（那是写作辅助，不是本 Agent）。已有图检查：白底、扁平矢量、英文短标签、不要长句堆在图里。
7. 输出 ReviewIssue，category=`FIGURE_PDF`。Vision 结论必须标明「语义判断」，程序结论标明「确定性解析」。

## Decision boundary

确定性优先；视觉语义其次。不得用语言模型猜测 DPI 或页码。
