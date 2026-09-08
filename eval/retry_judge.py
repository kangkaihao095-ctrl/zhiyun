#!/usr/bin/env python3
"""Retry LLM-as-judge for agents that failed with HTTP 400. Compact payloads only."""
from __future__ import annotations

import json
import ssl
import sys
import urllib.error
import urllib.request
from pathlib import Path

EVAL = Path(__file__).resolve().parent
sys.path.insert(0, str(EVAL))
from run_full_review_eval import GOLD, LOCAL_YML, PAPER_MD, REPORTS, cap, dashscope_chat, extract_json, load_llm_config, judge_agent

ARTS = REPORTS / "20260904-full-review-5-artifacts.json"
JUDGE = REPORTS / "20260904-full-review-5-judge.json"


def compact_agent(data: dict, agent: str) -> dict:
    out = {}
    for a in data["artifacts"]:
        if a.get("agent") != agent:
            continue
        if a.get("artifactType") == "Bundle":
            continue
        payload = a.get("payload")
        if isinstance(payload, str):
            payload = json.loads(payload)
        out[a["artifactType"]] = payload.get("body") if isinstance(payload, dict) else payload
    return out


def main() -> int:
    key, model, base = load_llm_config()
    data = json.loads(ARTS.read_text(encoding="utf-8"))
    judge = json.loads(JUDGE.read_text(encoding="utf-8"))
    paper = cap(PAPER_MD.read_text(encoding="utf-8"), 2200)
    task = data["task"]
    task_meta = {
        "taskId": task.get("id"),
        "status": task.get("status"),
        "workflow": task.get("workflow"),
        "sourceVersion": task.get("sourceVersion"),
        "candidateVersion": task.get("candidateVersion"),
        "checkpointAgent": task.get("checkpointAgent"),
        "live": True,
        "paperModel": model,
    }
    failed = [ag for ag, row in judge["agents"].items() if row.get("error") or not row.get("items")]
    if not failed:
        failed = list(GOLD)
    print("retry", failed, flush=True)
    for agent in failed:
        blob = compact_agent(data, agent)
        print(f"judge {agent} bytes={len(json.dumps(blob, ensure_ascii=False))}", flush=True)
        try:
            judged = judge_agent(
                key, model, base, agent, GOLD[agent], paper,
                cap(json.dumps(blob, ensure_ascii=False), 6000),
                task_meta,
            )
        except Exception as e:
            body = ""
            if isinstance(e.__cause__, urllib.error.HTTPError):
                body = e.__cause__.read().decode("utf-8", errors="replace")[:400]
            elif isinstance(e, urllib.error.HTTPError):
                body = e.read().decode("utf-8", errors="replace")[:400]
            # dashscope_chat raises HTTPError directly
            judged = {"agent": agent, "error": str(e)[:240], "errorBody": body, "coveragePct": 0, "items": []}
            print(" fail", judged["error"], body[:200], flush=True)
        judge["agents"][agent] = judged
        JUDGE.write_text(json.dumps(judge, ensure_ascii=False, indent=2), encoding="utf-8")
    print("updated", JUDGE, flush=True)
    return 0


if __name__ == "__main__":
    # Patch dashscope_chat to surface 400 body
    import run_full_review_eval as m

    orig = m.dashscope_chat

    def wrapped(key, model, base, system, user):
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
            headers={"Authorization": "Bearer " + key, "Content-Type": "application/json"},
            method="POST",
        )
        try:
            ctx = ssl.create_default_context()
            with urllib.request.urlopen(req, timeout=120, context=ctx) as resp:
                obj = json.loads(resp.read().decode("utf-8"))
        except urllib.error.HTTPError as e:
            err = e.read().decode("utf-8", errors="replace")
            raise RuntimeError(f"HTTP {e.code}: {err[:500]}") from None
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

    m.dashscope_chat = wrapped
    # also patch name used by judge_agent via import
    import types
    globals()["dashscope_chat"] = wrapped
    # judge_agent already bound dashscope_chat at import time
    import run_full_review_eval as rr
    rr.dashscope_chat = wrapped
    sys.exit(main())
