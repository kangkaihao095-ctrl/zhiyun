#!/usr/bin/env bash
# 重建全局 PUBLIC 知识索引（scope=PUBLIC，不是一租户一份）。
# 内置稿在 backend/src/main/resources/knowledge/*.md，启动时 PublicKnowledgeSeeder 会重建。
# 运营账号也可调用设置页「投稿规范运营」或本脚本。
set -euo pipefail
BASE="${ZHIYUN_API:-http://127.0.0.1:8080}"
EMAIL="${ZHIYUN_OPS_EMAIL:-demo@zhiyun.dev}"
PASSWORD="${ZHIYUN_OPS_PASSWORD:-demo123456}"

TOKEN="$(curl -sS -X POST "$BASE/api/auth/login" \
  -H 'Content-Type: application/json' \
  -d "{\"email\":\"$EMAIL\",\"password\":\"$PASSWORD\"}" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("token",""))')"

if [[ -z "$TOKEN" ]]; then
  echo "login failed" >&2
  exit 1
fi

curl -sS -X POST "$BASE/api/ops/knowledge/reindex" \
  -H "Authorization: Bearer $TOKEN"
echo
