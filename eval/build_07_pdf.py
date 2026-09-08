#!/usr/bin/env python3
"""Build a two-page US-Letter PDF with a low-quality figure box and a table.

Figure Agent can render page 1 (no XObject) via PDFBox firstRaster.
No third-party packages.
"""
from __future__ import annotations

import os
import zlib

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "papers", "07-eval-two-page-figure-table.pdf")


def esc(text: str) -> str:
    return text.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)")


def tj(x: float, y: float, text: str, size: float = 10) -> str:
    return f"BT /F1 {size:.1f} Tf {x:.1f} {y:.1f} Td ({esc(text)}) Tj ET"


def wrap(text: str, width: int) -> list[str]:
    words = text.split()
    lines: list[str] = []
    cur = ""
    for w in words:
        trial = (cur + " " + w).strip()
        if len(trial) <= width:
            cur = trial
        else:
            if cur:
                lines.append(cur)
            cur = w
    if cur:
        lines.append(cur)
    return lines


def page_stream(lines: list[str]) -> bytes:
    return ("\n".join(lines) + "\n").encode("latin-1", errors="replace")


def build() -> bytes:
    p1: list[str] = []
    y = 760
    p1.append(tj(72, y, "Camera-Ready Multi-Agent Review Under ACL Page Limits", 14))
    y -= 22
    p1.append(tj(72, y, "Abstract", 12))
    y -= 16
    abstract = (
        "Firstly, we propose a novel multi-agent pipeline that outperforms all prior work "
        "on citation integrity, figure quality, and camera-ready language. We claim that "
        "retrieval-augmented review eliminates hallucinated DOIs. Smith et al. (DOI "
        "10.1145/3290605.3300233) are cited as proving that Java metadata checks are "
        "unnecessary, which they do not. We further cite a fabricated report "
        "10.0000/ghost.doi as the sole evidence that our method gains 12 F1 points over "
        "Crossref lookup. BERT (DOI 10.18653/v1/N19-1423) is a real NAACL paper; we "
        "nonetheless claim it already solved citation hallucination. This two-page draft "
        "is intended for ACL, yet it was prepared on US Letter (612x792)."
    )
    for line in wrap(abstract, 92):
        p1.append(tj(72, y, line, 9))
        y -= 12

    y -= 8
    p1.append(tj(72, y, "1 Introduction", 12))
    y -= 16
    intro = (
        "In recent years, large language models have been used to review papers end-to-end. "
        "Moreover, a single model that criticizes, rewrites, and then certifies its own rewrite "
        "is a self-confirmation loop. Secondly, we discuss three contributions. Thirdly, we "
        "conclude with a summary. We show 99% accuracy. Style edits must protect numbers such "
        "as 300 dpi, 6 pages, 21 cm x 29.7 cm. BERT (DOI 10.18653/v1/N19-1423) is overstated. "
        "Figure 1 is a blurry JPEG screenshot of the pipeline. Caption is missing. Figure 3 "
        "is discussed before Figure 2."
    )
    for line in wrap(intro, 92):
        p1.append(tj(72, y, line, 9))
        y -= 12

    y -= 10
    # Blurry / low-contrast figure box (no caption)
    p1.append("0.75 g")
    p1.append("72 430 468 90 re f")
    p1.append("0.55 g")
    p1.append("90 455 80 40 re f")
    p1.append("190 455 80 40 re f")
    p1.append("290 455 80 40 re f")
    p1.append("390 455 80 40 re f")
    p1.append("0 g")
    p1.append(tj(98, 468, "Parse", 6))
    p1.append(tj(198, 468, "Chunk", 6))
    p1.append(tj(298, 468, "Embed", 6))
    p1.append(tj(398, 468, "Rerank", 6))
    p1.append(tj(72, 418, "Figure 1 is blurry; caption is missing; axis text unreadable after print.", 8))

    y = 400
    p1.append(tj(72, y, "2 Method", 12))
    y -= 16
    method = (
        "The intended chain is Parse, Chunk, Embedding, Vector Recall, Rerank, Top-5, Agent. "
        "Chunks inherit tenantId / manuscriptId / documentVersion / section. Citation existence "
        "is a Java + Crossref problem; the language model only judges whether Evidence supports "
        "a Claim. If Evidence is missing, the correct label is NOT_VERIFIED, not a guessed DOI. "
        "Revision Execution may emit a RevisionPatch and a candidate documentVersion. Final "
        "Verification must not trust the execution agent's self-report. ACL requires A4 "
        "(210 mm x 297 mm) two-column PDF. This draft records Letter geometry 612x792, which "
        "is wrong for ACL. Official manuscript must not be overwritten."
    )
    for line in wrap(method, 92):
        p1.append(tj(72, y, line, 9))
        y -= 12

    p2: list[str] = []
    y = 760
    p2.append(tj(72, y, "3 Experiments", 12))
    y -= 16
    exp = (
        "We report 99% accuracy on 20 examples without an ablation, without a held-out venue, "
        "and with a private split. The superiority claim is therefore an Evidence Gap. Table 2 "
        "is referenced in the text, but the only printed table is Table 1. We never define Table 2. "
        "The ghost paper 10.0000/ghost.doi is said to contain the 12-point DOI-accuracy gain. "
        "Smith et al. 10.1145/3290605.3300233 does not evaluate Crossref metadata verification."
    )
    for line in wrap(exp, 92):
        p2.append(tj(72, y, line, 9))
        y -= 12

    y -= 8
    p2.append(tj(72, y, "Table 1. Comparison with prior citation checkers (no ablation column).", 9))
    y -= 18
    # Table grid
    p2.append("0.2 w")
    rows_y = [y, y - 16, y - 32, y - 48, y - 64]
    p2.append(f"72 {rows_y[-1]} 468 64 re S")
    for ry in rows_y[1:-1]:
        p2.append(f"72 {ry} m 540 {ry} l S")
    for x in (180, 240, 320, 400):
        p2.append(f"{x} {rows_y[-1]} m {x} {rows_y[0]} l S")
    headers = [("System", 76), ("F1", 188), ("n", 250), ("Ablation", 328), ("Venue split", 408)]
    for label, x in headers:
        p2.append(tj(x, y - 12, label, 8))
    data = [
        ("Crossref lookup", "0.71", "20", "none", "none"),
        ("Ours (claimed)", "0.83", "20", "none", "none"),
        ("Ghost 10.0000/ghost.doi", "+12 F1", "-", "none", "none"),
    ]
    xs = [76, 188, 250, 328, 408]
    for i, row in enumerate(data):
        yy = y - 28 - 16 * i
        for cell, x in zip(row, xs):
            p2.append(tj(x, yy, cell, 8))

    y = rows_y[-1] - 24
    p2.append(tj(72, y, "4 Conclusion", 12))
    y -= 16
    conc = (
        "In conclusion, this paper has demonstrated the effectiveness of our approach. "
        "In summary, we have shown that the method works well in various scenarios. We will "
        "add experiments, fix the Letter/A4 mismatch, replace Figure 1, and add the missing "
        "Table 2 in a future version."
    )
    for line in wrap(conc, 92):
        p2.append(tj(72, y, line, 9))
        y -= 12

    y -= 10
    p2.append(tj(72, y, "References", 12))
    y -= 16
    refs = [
        "Devlin et al. BERT. NAACL 2019. DOI 10.18653/v1/N19-1423",
        "Amershi et al. Guidelines for Human-AI Interaction. CHI 2019. DOI 10.1145/3290605.3300233",
        "Ghost Author. Nonexistent study on citation accuracy. DOI 10.0000/ghost.doi",
        "Smith, A. and Lee, B. Dry-run verified work. DOI 10.1145/example.2019",
    ]
    for r in refs:
        p2.append(tj(72, y, r, 8))
        y -= 12

    streams = [page_stream(p1), page_stream(p2)]

    objs: list[bytes] = []

    def add(obj: bytes) -> int:
        objs.append(obj)
        return len(objs)

    add(b"<< /Type /Catalog /Pages 2 0 R >>")
    add(b"<< /Type /Pages /Kids [3 0 R 4 0 R] /Count 2 >>")
    add(
        b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] "
        b"/Contents 5 0 R /Resources << /Font << /F1 7 0 R >> >> >>"
    )
    add(
        b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] "
        b"/Contents 6 0 R /Resources << /Font << /F1 7 0 R >> >> >>"
    )
    for s in streams:
        add(b"<< /Length %d >>\nstream\n" % len(s) + s + b"\nendstream")
    add(b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>")

    out = bytearray(b"%PDF-1.4\n")
    offsets = [0]
    for i, body in enumerate(objs, start=1):
        offsets.append(len(out))
        out.extend(f"{i} 0 obj\n".encode())
        out.extend(body)
        out.extend(b"\nendobj\n")
    xref = len(out)
    out.extend(f"xref\n0 {len(objs)+1}\n".encode())
    out.extend(b"0000000000 65535 f \n")
    for off in offsets[1:]:
        out.extend(f"{off:010d} 00000 n \n".encode())
    out.extend(
        f"trailer << /Size {len(objs)+1} /Root 1 0 R >>\nstartxref\n{xref}\n%%EOF\n".encode()
    )
    return bytes(out)


def main() -> None:
    data = build()
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "wb") as f:
        f.write(data)
    print(f"wrote {OUT} bytes={len(data)}")


if __name__ == "__main__":
    main()
