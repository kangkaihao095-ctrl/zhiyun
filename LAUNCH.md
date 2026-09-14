# 智云上线检查

对照可演示/可交付，不是互联网大厂 SLA。口径见 `md/智云_MultiAgent项目.md` 与 `DEV_TASK.md`。

**结论（2026-09-14）：有条件上线。**

主路径与安全代码已闭环。任务对外号 `ZYT…`、流水对外号 `ZYL…`、云笺 `order_query` 日期过滤已在当前代码里。现在还不能点「可以上线」：compose、当前进程重启、双账号 404 必须人在本机跑完。

看板：[zhiyun-go-live.canvas.tsx](/Users/lumengkang/.cursor/projects/Users-lumengkang-Desktop/canvases/zhiyun-go-live.canvas.tsx)（可在对话旁打开）。

## 必须先完成

按顺序，不要跳。

1. **对外业务号与云笺日期过滤（已落地）**  
   云笺 `order_query`：今天/昨天/本周 → `from`/`to`，Java 参数化，模型不写 SQL。  
   任务对外号 `ZYT…`、流水对外号 `ZYL…`（内部仍是自增 PK；API `id` 为业务号，兼容旧数字主键）。  
   Flyway `V10__task_ledger_public_no.sql` 已在仓库；不要再当半成品跳过。

2. **拉起依赖**

```bash
cd zhiyun
docker compose up -d
```

确认本机可连：MySQL `3306`、Redis `6379`、RabbitMQ `5672`、Elasticsearch `9200`。

3. **用收口后的当前代码重启后端**（JDK 17，不要用 Java 26 跑产品进程）

```bash
export JAVA_HOME=/usr/local/opt/openjdk@17
export PATH="$JAVA_HOME/bin:$PATH"
export SPRING_PROFILES_ACTIVE=local
export ZHIYUN_LLM_MODE=auto
cd zhiyun/backend && mvn spring-boot:run
```

前端：`cd zhiyun/frontend && npm install && npm run dev`。

确认：`curl -s http://127.0.0.1:8080/actuator/health` 为 UP；页面用 `http://127.0.0.1:5173`。  
浏览器若出现「Failed to fetch」或「连不上服务器」，先看 8080 是否挂掉，不要先改前端。

4. **双账号 404 冒烟**（单测不能代替）  
   账号 A 上传稿件、开一条审校、下一笔订单。账号 B 用自己的 JWT 访问 A 的稿件、任务、`/report`、订单，必须 **404**。步骤见下节。

5. **若不只本机**  
   改 `JWT_SECRET`（≥32 且非仓库默认）；`ZHIYUN_MCP_TOKEN` 不要用 `dev-mcp-token`；`ZHIYUN_CORS_ORIGINS` 写前端 Origin，不要 `*`。`prod` 弱 JWT 会拒绝启动。

## 双账号冒烟（示例）

把 `TOKEN_A` / `TOKEN_B` 换成登录返回的 JWT。资源 id 用接口 JSON 里的 `id`（任务可能是 `ZYT…`，订单是 `ZY…`，稿件仍是数字）。

```bash
# A 注册
curl -s -X POST http://127.0.0.1:8080/api/auth/register \
  -H 'Content-Type: application/json' \
  -d '{"email":"golive-a@zhiyun.dev","password":"demo123456","displayName":"A"}'

# B 注册
curl -s -X POST http://127.0.0.1:8080/api/auth/register \
  -H 'Content-Type: application/json' \
  -d '{"email":"golive-b@zhiyun.dev","password":"demo123456","displayName":"B"}'
```

A 登录后：`GET /api/projects` → 上传 `POST /api/manuscripts` → `POST /api/manuscripts/{id}/reviews`（body `{ "workflow": "CITATION_ONLY" }`）→ `POST /api/orders`（需已有套餐）记下 `msId`、`taskId`、`orderId`。

B 必须 404：

```bash
curl -s -o /dev/null -w "%{http_code}\n" \
  -H "Authorization: Bearer $TOKEN_B" \
  http://127.0.0.1:8080/api/manuscripts/$MS_ID

curl -s -o /dev/null -w "%{http_code}\n" \
  -H "Authorization: Bearer $TOKEN_B" \
  http://127.0.0.1:8080/api/reviews/$TASK_ID

curl -s -o /dev/null -w "%{http_code}\n" \
  -H "Authorization: Bearer $TOKEN_B" \
  http://127.0.0.1:8080/api/reviews/$TASK_ID/report

curl -s -o /dev/null -w "%{http_code}\n" \
  -H "Authorization: Bearer $TOKEN_B" \
  http://127.0.0.1:8080/api/orders/$ORDER_ID
```

四条都应打印 `404`。也可用演示账号 `demo@zhiyun.dev` / `demo123456` 走完整演示：上传 → 选刊 → 三条 Workflow → Accept/Reject → 导出 → mock 充值 → 云笺问额度（刷新即新会话）。学科实验室账号见 README「演示账号」。

## 已知债（演示口径可带）

- Java 26 下 Mockito 可能无法 mock 具体类：产品用 JDK 17；测试用手写 stub。
- 评估接口与 Recall@5 只用于离线回归，**不要写成线上 SLA**。默认关；`prod` 始终关。

## 不要推翻

云笺内存会话、mock-pay、7+1 Agent、三条 Workflow、JWT `tid`、评估非 SLA。不要把云笺对话写入 Redis / localStorage。
