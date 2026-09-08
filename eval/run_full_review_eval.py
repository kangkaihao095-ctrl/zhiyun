#!/usr/bin/env python3
"""Live FULL_REVIEW + LLM-as-judge for Zhiyun paper agents.

Reads DashScope key from gitignored application-local.yml. Never prints the key.
Gold checks come from eval/agent-tasks.jsonl + skills SOP, not a new rubric.
"""
from __future__ import annotations

import json
import os
import re
import ssl
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
EVAL = Path(__file__).resolve().parent
BACKEND = ROOT / "backend"
REPORTS = EVAL / "reports"
PAPER_MD = EVAL / "papers" / "07-eval-two-page-figure-table.md"
PAPER_PDF = EVAL / "papers" / "07-eval-two-page-figure-table.pdf"
LOCAL_YML = BACKEND / "application-local.yml"
API = os.environ.get("ZHIYUN_API", "http://127.0.0.1:8080")
EMAIL = os.environ.get("ZHIYUN_EVAL_EMAIL", "demo@zhiyun.dev")
PASSWORD = os.environ.get("ZHIYUN_EVAL_PASSWORD", "demo123456")

# SOP items transferred from eval/agent-tasks.jsonl (A01–A40) onto paper 07,
# which plants the same defects. Skills override A28: ghost DOI is HUMAN_REQUIRED.
GOLD = {
    "CITATION_INTEGRITY": [
        ("A01", "幽灵 DOI 10.0000/ghost.doi 必须 status=NOT_VERIFIED，不得编替代 DOI"),
        ("A03", "存在性与 Claim 支持度分开写；相关 ≠ 存在，相关 ≠ 支持"),
        ("A05", "真 DOI 10.18653/v1/N19-1423 应走 AcademicSearch/Crossref，不得靠模型记忆"),
        ("A06", "产物中不得出现模型新编造的 DOI"),
        ("A07", "Evidence.source 应为 CROSSREF/WEB/MANUSCRIPT；无可信 Evidence 不得标 VERIFIED"),
        ("A19", "用幽灵 DOI 支撑 12 点增益的 Claim 不得标为已支持"),
    ],
    "FIGURE_PDF": [
        ("A20", "程序检查应标出 US Letter / 612x792，对 ACL A4 不合格"),
        ("A22", "应检出 Figure 1 模糊 / 印刷不可读（可标 Vision 语义判断）"),
        ("A23", "应检出 Figure 1 缺题注"),
        ("A24", "应检出 Table 2 被引用但未给出、或与 Table 1 编号不连续"),
        ("A25", "应讨论 pipeline 图质量（模糊截图 / 非矢量）"),
        ("SOP5", "Caption/表题问题 category=FIGURE_PDF；确定性 vs 语义判断要分开写"),
    ],
    "ACADEMIC_REVIEWER": [
        ("A14", "无 ablation 的 outperforms 标 Evidence Gap；不得指控数据造假"),
        ("A15", "99%/outperforms without ablation 必须作为弱点"),
        ("A17", "category 用 REVIEW 或 LOGIC，不要把 Firstly 当方法缺陷"),
        ("SOP4", "没有 Evidence 时不得武断说实验错误"),
    ],
    "ACADEMIC_STYLE": [
        ("A08", "应标出 Firstly / In conclusion 等机械连接词或空泛总结"),
        ("A09", "不得把 300 dpi、6 pages、DOI、99% 当文风问题去改"),
        ("A10", "产物不得出现 AcademicSearch / AcademicSearchTool"),
        ("SOP", "只出 ReviewIssue，不写正式稿；category=STYLE"),
    ],
    "REVISION_PLANNING": [
        ("A26", "补实验 / 新消融 / 99% 证据缺口 → HUMAN_REQUIRED 或 HYBRID，不得伪装成已可自动补实验"),
        ("A27", "文风 Issue → AI_AUTOMATABLE"),
        ("A28s", "幽灵 DOI / 关键引用最终选择 → HUMAN_REQUIRED（skills/planning.md，覆盖 A28 的 AI_OR_HYBRID 宽松项）"),
        ("A29", "页规格 / ACL A4 与 Letter 冲突可 HYBRID 或 HUMAN_REQUIRED"),
        ("A30", "输出 RevisionTask schema：taskId, issueId, kind, instruction, protectedFacts"),
    ],
    "REVISION_EXECUTION": [
        ("A31", "正式稿 sourceVersion 保持 1，不得覆盖 OFFICIAL"),
        ("A32", "应有候选 documentVersion 或明确说明未写候选"),
        ("A33", "只出 RevisionPatch；reason 不得写「已写入正式稿」"),
        ("A35", "patches 含 originalText / proposedText / issueId"),
        ("SOP1", "HUMAN_REQUIRED 任务不得生成 Patch"),
    ],
    "FINAL_VERIFICATION": [
        ("A36", "所有 VerificationResult.basedOnExecutionSelfReport 必须为 false"),
        ("A37", "任务终态应为 WAITING_ACCEPT（FULL_REVIEW 有修订时）"),
        ("A38", "不得把 Execution 自述当证据"),
        ("SOP4", "HUMAN_REQUIRED 项 stillHumanRequired=true 且不得 resolved=true"),
    ],
}


def load_llm_config() -> tuple[str, str, str]:
    text = LOCAL_YML.read_text(encoding="utf-8")
    section = None
    key = ""
    model = "qwen3.8-flash"
    base = "https://dashscope.aliyuncs.com/compatible-mode/v1"
    for raw in text.splitlines():
        if not raw.strip() or raw.lstrip().startswith("#"):
            continue
        if raw.startswith("zhiyun:"):
            section = "zhiyun"
            continue
        if raw.startswith("  ") and not raw.startswith("    "):
            section = raw.strip().rstrip(":")
            continue
        if section != "llm":
            continue
        name, _, val = raw.strip().partition(":")
        val = val.strip()
        if name == "api-key":
            key = val
        elif name == "paper-model":
            model = val or model
        elif name == "base-url":
            base = val or base
    if not key:
        raise SystemExit("application-local.yml 中没有 zhiyun.llm.api-key")
    return key, model, base.rstrip("/")


def http_json(method: str, url: str, token: str | None = None, body=None, timeout: int = 60):
    data = None
    headers = {"Accept": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    if body is not None:
        data = json.dumps(body).encode("utf-8")
        headers["Content-Type"] = "application/json"
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            raw = resp.read()
            if not raw:
                return {}
            return json.loads(raw.decode("utf-8"))
    except urllib.error.HTTPError as e:
        err = e.read().decode("utf-8", errors="replace")
        raise RuntimeError(f"{method} {url} -> {e.code} {err[:400]}") from e


def http_upload(url: str, token: str, project_id: int, path: Path):
    boundary = "----zhiyunEvalBoundary"
    filename = path.name
    file_bytes = path.read_bytes()
    mime = "application/pdf" if filename.endswith(".pdf") else "text/markdown"
    parts = []
    parts.append(
        f"--{boundary}\r\nContent-Disposition: form-data; name=\"projectId\"\r\n\r\n{project_id}\r\n".encode()
    )
    parts.append(
        (
            f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"{filename}\"\r\n"
            f"Content-Type: {mime}\r\n\r\n"
        ).encode()
        + file_bytes
        + b"\r\n"
    )
    parts.append(f"--{boundary}--\r\n".encode())
    body = b"".join(parts)
    req = urllib.request.Request(
        url,
        data=body,
        headers={
            "Authorization": "Bearer " + token,
            "Content-Type": f"multipart/form-data; boundary={boundary}",
            "Accept": "application/json",
        },
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=120) as resp:
        return json.loads(resp.read().decode("utf-8"))


def dashscope_chat(key: str, model: str, base: str, system: str, user: str) -> str:
    payload = {
        "model": model,
        "temperature": 0.1,
        "max_tokens": 2048,
        "enable_thinking": False,
        "messages": [
            {"role": "system", "content": system},
            {"role": "user", "content": user},
        ],
    }
    data = json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(
        base + "/chat/completions",
        data=data,
        headers={
            "Authorization": "Bearer " + key,
            "Content-Type": "application/json",
        },
        method="POST",
    )
    ctx = ssl.create_default_context()
    with urllib.request.urlopen(req, timeout=120, context=ctx) as resp:
        obj = json.loads(resp.read().decode("utf-8"))
    choices = obj.get("choices") or []
    if not choices:
        raise RuntimeError("judge empty choices")
    msg = choices[0].get("message") or {}
    content = msg.get("content") or msg.get("reasoning_content") or ""
    if isinstance(content, list):
        content = "".join(
            (part.get("text") or "") if isinstance(part, dict) else str(part) for part in content
        )
    if not str(content).strip():
        raise RuntimeError("judge empty content")
    return str(content)


def extract_json(text: str) -> dict:
    start = text.find("{")
    end = text.rfind("}")
    if start < 0 or end <= start:
        raise ValueError("judge 未返回 JSON")
    return json.loads(text[start : end + 1])


def cap(s: str, n: int) -> str:
    return s if len(s) <= n else s[:n] + "\n…[truncated]"


def summarize_artifacts(arts: list) -> dict:
    by_agent: dict[str, dict] = {}
    for a in arts:
        agent = a.get("agent") or "UNKNOWN"
        atype = a.get("artifactType") or "unknown"
        payload = a.get("payload")
        if isinstance(payload, str):
            try:
                payload = json.loads(payload)
            except json.JSONDecodeError:
                payload = {"raw": payload[:500]}
        bucket = by_agent.setdefault(agent, {"types": {}, "issueCount": 0, "summaries": []})
        bucket["types"][atype] = payload
        body = payload.get("body") if isinstance(payload, dict) else payload
        if atype == "ReviewIssue":
            issues = body if isinstance(body, list) else []
            bucket["issueCount"] += len(issues)
            for iss in issues[:8]:
                if isinstance(iss, dict):
                    bucket["summaries"].append(
                        {
                            "issueId": iss.get("issueId"),
                            "severity": iss.get("severity"),
                            "category": iss.get("category"),
                            "summary": iss.get("summary"),
                        }
                    )
        if atype == "Evidence" and isinstance(body, list):
            bucket["evidenceCount"] = len(body)
            bucket["evidenceStatus"] = [e.get("status") for e in body if isinstance(e, dict)]
            dois = []
            for e in body:
                if not isinstance(e, dict):
                    continue
                paper = e.get("paper") or {}
                doi = paper.get("doi") if isinstance(paper, dict) else None
                dois.append({"doi": doi or e.get("excerpt"), "status": e.get("status")})
            bucket["dois"] = dois
        if atype == "RevisionTask" and isinstance(body, list):
            bucket["taskCount"] = len(body)
            bucket["kinds"] = [t.get("kind") for t in body if isinstance(t, dict)]
        if atype == "RevisionPatch" and isinstance(body, list):
            bucket["patchCount"] = len(body)
        if atype == "VerificationResult" and isinstance(body, list):
            bucket["verificationCount"] = len(body)
            bucket["selfReport"] = [v.get("basedOnExecutionSelfReport") for v in body if isinstance(v, dict)]
            bucket["resolved"] = [v.get("resolved") for v in body if isinstance(v, dict)]
            bucket["stillHuman"] = [v.get("stillHumanRequired") for v in body if isinstance(v, dict)]
    return by_agent


def judge_agent(key, model, base, agent, checks, paper_excerpt, artifact_blob, task_meta) -> dict:
    system = (
        "你是智云评估 Judge，基座与论文 Agent 相同：阿里云百炼千问。"
        "只按给定 SOP / gold 条目打 0 或 1，禁止发明新量表。"
        "必须引用产物或原文中的具体短语作为理由。"
        "只输出 JSON。"
    )
    items = [{"id": i, "criterion": c} for i, c in checks]
    user = json.dumps(
        {
            "agent": agent,
            "task": task_meta,
            "goldChecks": items,
            "paperExcerpt": paper_excerpt,
            "artifacts": artifact_blob,
            "outputSchema": {
                "agent": agent,
                "items": [
                    {
                        "id": "Axx",
                        "pass": True,
                        "score": 1,
                        "evidenceQuote": "短引文",
                        "reason": "对照 SOP 的一句理由",
                    }
                ],
                "coveragePct": 0,
                "schemaOk": True,
                "figureOrTableNote": "仅 FIGURE/REVIEWER/STYLE 填写图表是否被检出，否则空字符串",
            },
        },
        ensure_ascii=False,
    )
    raw = dashscope_chat(key, model, base, system, cap(user, 14000))
    parsed = extract_json(raw)
    items_out = parsed.get("items") or []
    hits = sum(1 for it in items_out if it.get("pass") or it.get("score") == 1)
    total = max(len(checks), 1)
    parsed["coveragePct"] = round(100.0 * hits / total, 1)
    parsed["rawJudgeText"] = raw
    return parsed


def ensure_quota(token: str) -> dict:
    me = http_json("GET", f"{API}/api/me", token)
    quota = int(me.get("quota") or 0)
    if quota >= 1:
        return me
    plans = http_json("GET", f"{API}/api/plans", token)
    plan_id = None
    for p in plans:
        if str(p.get("code", "")).lower() == "starter" or p.get("quotaAmount") or p.get("quota_amount"):
            plan_id = p.get("id")
            break
    if plan_id is None and plans:
        plan_id = plans[0].get("id")
    if plan_id is None:
        raise RuntimeError("额度不足且没有可购买套餐")
    order = http_json("POST", f"{API}/api/orders", token, {"planId": plan_id})
    http_json("POST", f"{API}/api/orders/{order['id']}/mock-pay", token)
    return http_json("GET", f"{API}/api/me", token)


def main() -> int:
    REPORTS.mkdir(parents=True, exist_ok=True)
    stamp = datetime.now(timezone.utc).astimezone().strftime("%Y%m%d")
    key, model, base = load_llm_config()
    paper_text = PAPER_MD.read_text(encoding="utf-8")
    upload_path = PAPER_PDF if PAPER_PDF.exists() else PAPER_MD

    login = http_json("POST", f"{API}/api/auth/login", body={"email": EMAIL, "password": PASSWORD})
    token = login["token"]
    me = ensure_quota(token)
    llm = http_json("GET", f"{API}/api/llm/status", token)
    rag = http_json("GET", f"{API}/api/eval/rag", token, timeout=180)
    agents_cat = http_json("GET", f"{API}/api/eval/agents", token)

    projects = http_json("GET", f"{API}/api/projects", token)
    if projects:
        project_id = projects[0]["id"]
    else:
        created = http_json("POST", f"{API}/api/projects", token, {"name": "eval-full-review-07"})
        project_id = created["id"]

    ms = http_upload(f"{API}/api/manuscripts", token, project_id, upload_path)
    manuscript_id = ms["id"]
    task = http_json(
        "POST",
        f"{API}/api/manuscripts/{manuscript_id}/reviews",
        token,
        {"workflow": "FULL_REVIEW"},
    )
    task_id = task["id"]
    started = datetime.now(timezone.utc).astimezone().isoformat(timespec="seconds")
    print(f"FULL_REVIEW taskId={task_id} manuscriptId={manuscript_id} file={upload_path.name}", flush=True)

    deadline = time.time() + 20 * 60
    last = task
    while time.time() < deadline:
        last = http_json("GET", f"{API}/api/reviews/{task_id}", token)
        status = last.get("status")
        ck = last.get("checkpointAgent")
        print(f"  status={status} checkpoint={ck}", flush=True)
        if status in {"DONE", "WAITING_ACCEPT", "FAILED"}:
            break
        time.sleep(12)
    else:
        print("timeout waiting for review", flush=True)

    arts = []
    try:
        arts = http_json("GET", f"{API}/api/reviews/{task_id}/artifacts", token)
    except Exception as e:
        print(f"artifacts fetch failed: {e}", flush=True)
    diff = {}
    try:
        diff = http_json("GET", f"{API}/api/reviews/{task_id}/diff", token)
    except Exception as e:
        print(f"diff fetch failed: {e}", flush=True)

    summary = summarize_artifacts(arts)
    artifacts_path = REPORTS / f"{stamp}-full-review-{task_id}-artifacts.json"
    artifacts_path.write_text(
        json.dumps(
            {"task": last, "manuscriptId": manuscript_id, "file": upload_path.name, "artifacts": arts},
            ensure_ascii=False,
            indent=2,
        ),
        encoding="utf-8",
    )
    diff_path = REPORTS / f"{stamp}-full-review-{task_id}-diff.json"
    diff_path.write_text(json.dumps(diff, ensure_ascii=False, indent=2), encoding="utf-8")

    task_meta = {
        "taskId": task_id,
        "status": last.get("status"),
        "workflow": last.get("workflow"),
        "sourceVersion": last.get("sourceVersion"),
        "candidateVersion": last.get("candidateVersion"),
        "checkpointAgent": last.get("checkpointAgent"),
        "errorMessage": last.get("errorMessage"),
        "live": llm.get("live"),
        "paperModel": llm.get("paperModel") or model,
        "visionModel": llm.get("visionModel"),
    }

    judge_out = {
        "protocol": "agent-tasks.jsonl A01-A40 + skills/*.md SOP; coverage = gold hits / items",
        "model": model,
        "enable_thinking": False,
        "startedAt": started,
        "finishedAt": datetime.now(timezone.utc).astimezone().isoformat(timespec="seconds"),
        "task": task_meta,
        "ragRecallAt5": rag,
        "agentCatalog": agents_cat,
        "quotaAfterStart": me.get("quota"),
        "agents": {},
    }

    paper_excerpt = cap(paper_text, 3500)
    for agent, checks in GOLD.items():
        blob = summary.get(agent, {})
        # attach compact bundle if present
        types = blob.get("types") or {}
        compact = {k: types[k] for k in types if k != "Bundle"}
        if "Bundle" in types:
            compact["Bundle"] = types["Bundle"]
        print(f"judge {agent} ...", flush=True)
        try:
            judged = judge_agent(
                key,
                model,
                base,
                agent,
                checks,
                paper_excerpt,
                cap(json.dumps(compact, ensure_ascii=False), 8000),
                task_meta,
            )
        except Exception as e:
            judged = {"agent": agent, "error": str(e)[:300], "coveragePct": 0, "items": []}
        judge_out["agents"][agent] = judged

    judge_path = REPORTS / f"{stamp}-full-review-{task_id}-judge.json"
    judge_path.write_text(json.dumps(judge_out, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"wrote {judge_path}", flush=True)
    print(f"status={last.get('status')} candidate={last.get('candidateVersion')}", flush=True)
    return 0 if last.get("status") in {"DONE", "WAITING_ACCEPT"} else 2


if __name__ == "__main__":
    sys.exit(main())
