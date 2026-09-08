# Camera-Ready Multi-Agent Review Under ACL Page Limits

%%PDF_META pageSize=612x792 pages=2 figures=1

## Abstract
Firstly, we propose a novel multi-agent pipeline that outperforms all prior work on citation integrity, figure quality, and camera-ready language. We claim that retrieval-augmented review **eliminates** hallucinated DOIs. Smith et al. (DOI 10.1145/3290605.3300233) are cited as proving that Java metadata checks are unnecessary, which they do not. We further cite a fabricated report **10.0000/ghost.doi** as the sole evidence that our method gains 12 F1 points over Crossref lookup. BERT (DOI 10.18653/v1/N19-1423) is a real NAACL paper; we nonetheless claim it already solved citation hallucination. This two-page draft is intended for ACL, yet it was prepared on US Letter (`612x792`).

## 1 Introduction
In recent years, large language models have been used to review papers end-to-end. Moreover, a single model that criticizes, rewrites, and then certifies its own rewrite is a self-confirmation loop. Secondly, we discuss three contributions. Thirdly, we conclude with a summary.

Our contributions are as follows. (1) We split review into specialized agents that pass structured artifacts rather than full chat history. (2) We isolate tenants with a shared index plus `tenantId` filters. (3) We show 99% accuracy. The official manuscript must not be overwritten by language edits. Style edits must protect numbers such as **300 dpi**, **6 pages**, **21 cm × 29.7 cm**, and **1 元 = 1 额度**.

Related work is thin. BERT (Devlin et al., DOI 10.18653/v1/N19-1423) is a masked language model for NLP; we nonetheless claim it “already solved citation hallucination,” which overstates the paper. Attention-based models are mentioned without a venue-correct template discussion.

Figure 1 is a blurry JPEG screenshot of the pipeline. Caption is missing. The file was heavily compressed, so axis text is unreadable after print. We do not provide a vector PDF object for the diagram. Figure 3 is discussed before Figure 2.

```
[Figure 1 — blurry pipeline JPEG, no caption]
  Parse --> Chunk --> Embed --> Recall --> Rerank
  (axis labels < 6 pt, compressed JPEG, unreadable in print)
```

## 2 Method
The intended chain is `Parse → Chunk → Embedding → Vector Recall → Rerank → Top-5 → Agent`. Chunks inherit `tenantId / manuscriptId / documentVersion / section`. Citation existence is a Java + Crossref problem; the language model only judges whether Evidence supports a Claim. If Evidence is missing, the correct label is `NOT_VERIFIED`, not a guessed DOI.

Revision Execution may emit a `RevisionPatch` and a candidate `documentVersion`. Final Verification must not trust the execution agent’s self-report.

This draft mixes venues. ACL requires A4 (210 mm × 297 mm) two-column PDF with embedded fonts. NeurIPS uses US Letter, single column, and no Word. IEEE camera-ready usually passes PDF eXpress. The header above records Letter geometry (`612x792`), which is wrong for ACL.

## 3 Experiments
We report **99% accuracy on 20 examples** without an ablation, without a held-out venue, and with a private split. The superiority claim is therefore an Evidence Gap: a high number on a tiny set does not support “outperforms all prior work.” Table 2 is referenced in the text, but the only printed table is Table 1 below. We never define Table 2.

Table 1. Comparison with prior citation checkers (no ablation column).

| System | F1 | n | Ablation | Venue split |
|---|---|---|---|---|
| Crossref lookup | 0.71 | 20 | none | none |
| Ours (claimed) | 0.83 | 20 | none | none |
| Ghost paper 10.0000/ghost.doi | +12 F1 | — | none | none |

The ghost paper 10.0000/ghost.doi is said to contain the 12-point DOI-accuracy gain. No independent source is attached. Smith et al. 10.1145/3290605.3300233 is a CHI paper on human–AI interaction; it does not evaluate Crossref metadata verification.

## 4 Conclusion
In conclusion, this paper has demonstrated the effectiveness of our approach. In summary, we have shown that the method works well in various scenarios. We will add experiments, fix the Letter/A4 mismatch, replace Figure 1, and add the missing Table 2 in a future version.

## References
- Devlin, J., Chang, M., Lee, K., and Toutanova, K. BERT: Pre-training of Deep Bidirectional Transformers for Language Understanding. NAACL 2019. DOI 10.18653/v1/N19-1423
- Amershi, S., Weld, D., Vorvoreanu, M., et al. Guidelines for Human-AI Interaction. CHI 2019. DOI 10.1145/3290605.3300233
- Ghost Author. Nonexistent study on citation accuracy. DOI 10.0000/ghost.doi
- Smith, A. and Lee, B. Dry-run verified work. DOI 10.1145/example.2019
