# API 与 MCP 调用示例

以下命令假设服务运行在 `http://localhost:8080`。

```bash
export BASE_URL=http://localhost:8080
```

## 可选持久化启动与重启读取

普通 `mvn spring-boot:run` 使用内存仓储。需要让会话、评估和人工复核跨进程重启保留时：

```bash
SPRING_PROFILES_ACTIVE=persistent-demo mvn spring-boot:run
```

应用会在 `data/underwriting.mv.db` 创建 H2 文件。先按后文示例创建评估和人工复核，保存返回的
`evaluation_id`，停止并以相同 Profile 重启，再执行：

```bash
curl -sS "$BASE_URL/api/v1/underwriting/evaluations/${evaluation_id}" | python3 -m json.tool
curl -sS "$BASE_URL/api/v1/underwriting/evaluations/${evaluation_id}/review" | python3 -m json.tool
curl -sS "$BASE_URL/api/v1/underwriting/evaluations/${evaluation_id}/report"
```

`PERSISTENT_DB_URL` 可以覆盖文件位置，例如
`jdbc:h2:file:/tmp/underwriting-demo;DB_CLOSE_ON_EXIT=FALSE`。该 Profile 不持久化知识库、Prompt 和
`Idempotency-Key` 注册表，也不代替生产 PostgreSQL/Flyway/事务方案。

## 健康、OpenAPI 与 Swagger

```bash
curl --fail "$BASE_URL/actuator/health"
curl --fail "$BASE_URL/v3/api-docs" | python3 -m json.tool
open "$BASE_URL/swagger-ui/index.html"
```

## 虚构演示场景 `/api/v1/demo/scenarios`

先查看四组场景摘要，再查看单个场景的完整五类业务事实：

```bash
curl -sS "$BASE_URL/api/v1/demo/scenarios" | python3 -m json.tool
curl -sS "$BASE_URL/api/v1/demo/scenarios/P-3001" | python3 -m json.tool
```

响应同时包含稳定的英文枚举和中文显示值：

```json
{
  "policyNo": "P-3001",
  "name": "暴雨暴露商贸仓库",
  "expectedResult": {
    "decision": "MANUAL_REVIEW",
    "decisionLabel": "人工复核",
    "riskLevel": "MEDIUM",
    "riskLevelLabel": "中风险",
    "riskScore": 40
  },
  "sumInsuredDisplay": "1,200 万元"
}
```

场景接口只读取虚构教学数据，不创建会话，也不执行核保评估。

## 会话 `/api/v1/sessions`

```bash
curl -sS -X POST "$BASE_URL/api/v1/sessions" | python3 -m json.tool
curl -sS "$BASE_URL/api/v1/sessions/SES-替换为返回值" | python3 -m json.tool
```

## 核保评估 `/api/v1/underwriting/evaluations`

不传 `sessionId` 时自动创建会话：

```bash
curl -sS -X POST "$BASE_URL/api/v1/underwriting/evaluations" \
  -H 'Content-Type: application/json' \
  -H 'X-Trace-Id: interview-demo-001' \
  -H 'Idempotency-Key: interview-demo-001' \
  -d '{"policyNo":"P-1001","question":"暴雨风险较高，这张仓库财产险能否承保？"}' \
  | python3 -m json.tool
```

把上面的请求原样执行两次：第一次返回 `201`、响应头 `Idempotency-Replayed: false`；第二次
不再执行七步 Agent，而是返回相同评估编号、HTTP `200` 和 `Idempotency-Replayed: true`。
同一个键改用不同 `policyNo`、`sessionId` 或 `question` 会返回 `409`：

```json
{
  "status": 409,
  "errorCode": "IDEMPOTENCY_KEY_CONFLICT",
  "detail": "Idempotency-Key has already been used with a different request"
}
```

键只能使用字母、数字、点、下划线、冒号和连字符，长度 1–128。失败执行不缓存；同一个键可重试。

评估响应中的关键来源快照如下。真实 SHA-256、分数和命中词取决于当前激活 Prompt 与检索结果：

```json
{
  "evidence": [
    {
      "documentId": "RULE-RAIN-001",
      "chunkId": "RULE-RAIN-001-V1-CHUNK-0",
      "knowledgeVersion": 1,
      "score": 0.91,
      "vectorScore": 0.86,
      "lexicalScore": 1.0,
      "retrievalMode": "HYBRID",
      "matchedTerms": ["暴雨", "红色"]
    }
  ],
  "modelResponse": {
    "provider": "mock",
    "model": "deterministic-underwriting-mock",
    "attempts": 1,
    "fallbackUsed": false,
    "prompt": {
      "code": "underwriting-analysis",
      "version": 1,
      "templateSha256": "64位小写十六进制模板指纹"
    }
  },
  "ruleSet": {
    "version": "property-underwriting-default-v1",
    "sha256": "64位小写十六进制规则集指纹",
    "ruleCount": 5
  }
}
```

`templateSha256` 对模板正文计算，不包含渲染后注入的保单事实。完整渲染 Prompt 只传给模型网关，不写入评估响应或
持久化 JSON。`ruleSet.sha256` 对规则集版本、基础分和全部规则规范化清单计算，不只是本次命中规则。`persistent-demo`
可以读取升级前记录；旧记录缺少的 Prompt/规则集/知识来源字段会返回 `version=0`、`ruleCount=0`、`UNKNOWN` 或
`unavailable`，明确表示历史来源不可用。

```bash
curl -sS "$BASE_URL/api/v1/underwriting/evaluations/EVAL-替换为返回值" | python3 -m json.tool
curl -sS "$BASE_URL/api/v1/underwriting/evaluations" | python3 -m json.tool
```

### 决策重演与漂移分析 `/{evaluationId}/replays`

先创建并保存一次评估，把编号放入 `baseline_id`，再用当前业务事实、知识、Prompt、规则集和模型重新运行：

```bash
baseline_id='EVAL-替换为真实编号'
replay_json=$(curl -sS --fail-with-body -X POST \
  "$BASE_URL/api/v1/underwriting/evaluations/${baseline_id}/replays" \
  -H "Idempotency-Key: replay-${baseline_id}")
printf '%s' "$replay_json" | python3 -m json.tool
replay_id=$(printf '%s' "$replay_json" \
  | python3 -c 'import json,sys; print(json.load(sys.stdin)["replay"]["id"])')
```

响应包含三个核心对象：

- `baseline`：完全不修改的历史评估；
- `replay`：新保存的评估，`replayedFromEvaluationId` 指向基线，并使用隔离的新会话；
- `drift`：结构化差异，包括 `decisionChange`、`riskScoreDelta`、增加/移除规则、增加/移除证据、共同证据分数变化，
  以及 `promptChanged`、`ruleSetChanged`、`modelChanged`。

`drift.severity` 只有三个低基数值：完全一致为 `STABLE`；结论/风险分/规则不变、但 Prompt/规则集/模型/证据发生变化
为 `SOURCE_DRIFT`；结论、风险等级/分数或规则命中变化为 `DECISION_DRIFT`。`decisionChange=WEAKENED` 表示重演结论
比基线更宽松，应重点复核变化依据。

相同 `Idempotency-Key` 和同一基线再次提交返回原重演评估、HTTP `200` 和
`Idempotency-Replayed: true`；同键用于其他基线或普通评估会返回 `409`。重演结果可在以后重新读取差异：

```bash
curl -sS "$BASE_URL/api/v1/underwriting/evaluations/${replay_id}/drift" \
  | python3 -m json.tool
curl -sS "$BASE_URL/actuator/metrics/underwriting.decision.replays" \
  | python3 -m json.tool
```

对普通评估调用 `/{evaluationId}/drift` 返回 `400 EVALUATION_NOT_REPLAY`。重演的语义是“使用当前依赖重新评估并解释差异”，
不是复现历史随机模型文本；如果要做严格历史复现，还必须持久化当时业务事实快照并使用可寻址模型版本。

### Actuator 运行指标

至少提交一次评估后查询：

```bash
curl -sS "$BASE_URL/actuator/metrics/underwriting.evaluation.submissions" | python3 -m json.tool
curl -sS "$BASE_URL/actuator/metrics/underwriting.evaluation.duration" | python3 -m json.tool
curl -sS "$BASE_URL/actuator/metrics/underwriting.evaluation.decisions" | python3 -m json.tool
```

提交计数和耗时按 `outcome=created|replayed|conflict|failed` 聚合；决策计数只记录真实 Agent
执行，按 `decision` 和 `risk_level` 聚合，不把重放请求重复计入业务结论。

提交人工复核后还可以查询：

```bash
curl -sS "$BASE_URL/actuator/metrics/underwriting.human.reviews" | python3 -m json.tool
curl -sS "$BASE_URL/actuator/metrics/underwriting.human.review.delay" | python3 -m json.tool
```

二者只使用 `outcome` 和 `relationship` 枚举标签，不使用人员、评估或保单编号。

## 异步核保任务 `/api/v1/underwriting/tasks`

提交任务时立即返回 `202 Accepted`、`Location`、`Idempotency-Replayed: false` 和当前任务快照：

```bash
task_json=$(curl -i -sS --fail-with-body \
  -X POST "$BASE_URL/api/v1/underwriting/tasks" \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: async-p1001-001' \
  -d '{"policyNo":"P-1001","question":"后台执行完整七步核保。"}')
```

实际脚本可不加 `-i`，提取任务编号后轮询：

```bash
task_body=$(curl -sS --fail-with-body \
  -X POST "$BASE_URL/api/v1/underwriting/tasks" \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: async-p2001-001' \
  -d '{"policyNo":"P-2001","question":"后台判断这张办公楼保单。"}')

task_id=$(printf '%s' "$task_body" \
  | python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])')

curl -sS "$BASE_URL/api/v1/underwriting/tasks/${task_id}" | python3 -m json.tool
curl -sS "$BASE_URL/api/v1/underwriting/tasks" | python3 -m json.tool
```

状态只会按 `PENDING → RUNNING → SUCCEEDED/FAILED` 转换。成功响应中的 `evaluationId` 指向普通评估
查询/报告接口；失败任务返回 `failure.errorCode` 与安全消息，`evaluationId` 为 `null`。相同幂等键和载荷
再次提交返回原任务、HTTP `200` 和 `Idempotency-Replayed: true`；同键异参返回 `409`。

任务运行指标：

```bash
curl -sS "$BASE_URL/actuator/metrics/underwriting.task.submissions" | python3 -m json.tool
curl -sS "$BASE_URL/actuator/metrics/underwriting.task.transitions" | python3 -m json.tool
curl -sS "$BASE_URL/actuator/metrics/underwriting.task.duration" | python3 -m json.tool
```

任务状态和任务幂等槽位当前是单实例内存数据，即使启用 `persistent-demo` 也不会恢复正在排队或运行的任务；
但已经成功生成的会话与评估会由该 Profile 持久化。生产环境需要持久化任务表/工作流引擎、租约、心跳、
超时取消、重试策略和补偿，而不是只换一个更大的线程池。

### 可重复的灾害平台降级演示

先停止普通进程，然后使用专用 Profile 启动：

```bash
SPRING_PROFILES_ACTIVE=degraded-demo mvn spring-boot:run
```

提交 `P-2001`：

```bash
curl -sS -X POST "$BASE_URL/api/v1/underwriting/evaluations" \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: degraded-demo-p2001' \
  -d '{"policyNo":"P-2001","question":"灾害平台不可用时是否可以自动承保？"}' \
  | python3 -m json.tool
```

关键响应片段：

```json
{
  "decision": "MANUAL_REVIEW",
  "riskLevel": "LOW",
  "riskScore": 10,
  "degradations": [{
    "code": "NON_CRITICAL_TOOL_UNAVAILABLE",
    "toolName": "GET_DISASTER_RISK",
    "errorCode": "TOOL_CALL_FAILED",
    "decisionFloor": "MANUAL_REVIEW"
  }],
  "stepTraces": [
    {"step": "BUSINESS_DATA_COLLECTION", "status": "DEGRADED",
     "errorCode": "NON_CRITICAL_TOOL_UNAVAILABLE"}
  ]
}
```

工具轨迹中的 `GET_DISASTER_RISK` 状态为 `FAILED`；后续规则、模型和持久化步骤仍可完成。下载该
评估的 Markdown 报告会出现“数据质量与安全降级”章节。这个 Profile 只影响 `P-2001`，且仅用于
教学和面试，不应作为生产故障注入机制。

发生一次降级后可查询对应指标：

```bash
curl -sS "$BASE_URL/actuator/metrics/underwriting.agent.degradations" | python3 -m json.tool
```

### 人工复核反馈 `/{evaluationId}/review`

先创建一条需要人工复核的虚构评估：

```bash
evaluation_json=$(curl -sS --fail-with-body \
  -X POST "$BASE_URL/api/v1/underwriting/evaluations" \
  -H 'Content-Type: application/json' \
  -d '{"policyNo":"P-1001","question":"请人工确认是否可以附条件承保。"}')

evaluation_id=$(printf '%s' "$evaluation_json" \
  | python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])')

curl -sS --fail-with-body \
  -X POST "$BASE_URL/api/v1/underwriting/evaluations/${evaluation_id}/review" \
  -H 'Content-Type: application/json' \
  -d '{
    "reviewerId":"UW-DEMO-001",
    "outcome":"APPROVED",
    "comment":"整改材料已核验，同意附条件承保。",
    "conditions":["提高免赔额至 10 万元","每季度复查排水设施"]
  }' | python3 -m json.tool
```

关键响应片段：

```json
{
  "evaluationId": "EVAL-...",
  "reviewerId": "UW-DEMO-001",
  "outcome": "APPROVED",
  "relationship": "RESOLVED_MANUAL_REVIEW",
  "conditions": ["提高免赔额至 10 万元", "每季度复查排水设施"]
}
```

读取单条复核或导出当前 Demo 中的全部复核：

```bash
curl -sS "$BASE_URL/api/v1/underwriting/evaluations/${evaluation_id}/review" | python3 -m json.tool
curl -sS "$BASE_URL/api/v1/underwriting/reviews" | python3 -m json.tool
```

同一评估只允许创建一次；再次 `POST` 返回 `409 HUMAN_REVIEW_ALREADY_EXISTS`。系统保留原始 Agent
建议，不把人工结果回写覆盖。生产环境导出反馈前必须进行权限校验、字段脱敏、用途审批和留存治理。

### 下载中文 Markdown 报告 `/{evaluationId}/report`

报告接口只读取已经保存的评估，不会再次运行七步 Agent。下面先用虚构保单 `P-1001` 创建评估，再保存报告和响应头：

```bash
evaluation_json=$(curl -sS --fail-with-body \
  -X POST "$BASE_URL/api/v1/underwriting/evaluations" \
  -H 'Content-Type: application/json' \
  -d '{"policyNo":"P-1001","question":"请生成一份便于讲解的核保结论。"}')

evaluation_id=$(printf '%s' "$evaluation_json" \
  | python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])')

curl -sS --fail-with-body \
  -D report-headers.txt \
  -o "underwriting-report-${evaluation_id}.md" \
  "$BASE_URL/api/v1/underwriting/evaluations/${evaluation_id}/report"

grep -i '^Content-' report-headers.txt
sed -n '1,80p' "underwriting-report-${evaluation_id}.md"
```

稳定的路径模板是 `GET /api/v1/underwriting/evaluations/{evaluationId}/report`。成功响应使用 `text/markdown;charset=UTF-8`，并通过 `Content-Disposition` 给出 `underwriting-report-{evaluationId}.md` 附件名；评估不存在时仍返回统一的 `EVALUATION_NOT_FOUND` Problem Details。

## 知识文档 `/api/v1/knowledge/documents`

该列表只返回当前 `PUBLISHED` 文档；草稿和历史版本使用版本接口查询：

```bash
curl -sS "$BASE_URL/api/v1/knowledge/documents" | python3 -m json.tool
```

创建 v1 草稿，响应状态为 `DRAFT`，此时内容不会进入检索：

```bash
curl -sS -X POST "$BASE_URL/api/v1/knowledge/documents" \
  -H 'Content-Type: application/json' \
  -d '{
    "id":"DEMO-CLAUSE-001",
    "title":"演示附加条款",
    "type":"PRODUCT_CLAUSE",
    "productCode":"PROPERTY",
    "content":"暴雨红色风险区域应转人工复核，并核验防洪整改证明。",
    "metadata":{"source":"interview-demo"}
  }' | python3 -m json.tool
```

发布 v1 并查询当前文档与版本历史：

```bash
curl -sS -X POST \
  "$BASE_URL/api/v1/knowledge/documents/DEMO-CLAUSE-001/versions/1/publish" \
  | python3 -m json.tool

curl -sS "$BASE_URL/api/v1/knowledge/documents/DEMO-CLAUSE-001" | python3 -m json.tool
curl -sS "$BASE_URL/api/v1/knowledge/documents/DEMO-CLAUSE-001/versions" | python3 -m json.tool
```

创建 v2 草稿。只允许同时存在一个草稿；重复创建返回 `409 KNOWLEDGE_DRAFT_EXISTS`：

```bash
curl -sS -X POST \
  "$BASE_URL/api/v1/knowledge/documents/DEMO-CLAUSE-001/versions" \
  -H 'Content-Type: application/json' \
  -d '{
    "title":"演示附加条款修订版",
    "type":"PRODUCT_CLAUSE",
    "productCode":"PROPERTY",
    "content":"修订版要求核验防洪整改证明和应急预案。",
    "metadata":{"source":"interview-demo-v2"}
  }' | python3 -m json.tool
```

发布 v2 后，v1 自动成为 `RETIRED`，索引中该文档的全部 v1 分块一次性替换为 v2 分块：

```bash
curl -sS -X POST \
  "$BASE_URL/api/v1/knowledge/documents/DEMO-CLAUSE-001/versions/2/publish" \
  | python3 -m json.tool
```

下线当前版本后，该文档不再参与检索，但历史仍可通过 `/versions` 查询：

```bash
curl -sS -X POST \
  "$BASE_URL/api/v1/knowledge/documents/DEMO-CLAUSE-001/versions/2/retire" \
  | python3 -m json.tool
```

非法重复发布、发布已下线版本或下线草稿返回 `409 INVALID_KNOWLEDGE_TRANSITION`。一条可重复的独立演示命令是：

```bash
bash scripts/knowledge-lifecycle-demo.sh
```

## RAG 检索 `/api/v1/knowledge/search`

```bash
curl -sS -X POST "$BASE_URL/api/v1/knowledge/search" \
  -H 'Content-Type: application/json' \
  -d '{"query":"仓库暴雨红色风险如何核保","topK":4,"productCode":"PROPERTY"}' \
  | python3 -m json.tool
```

`documentType` 可传 `PRODUCT_CLAUSE`、`UNDERWRITING_RULE`、`RISK_GUIDE` 或 `HISTORICAL_CASE`。
响应除分块外还包含融合 `score`、`vectorScore`、`lexicalScore`、`mode` 和最多 12 个
`matchedTerms`。`mode` 为 `HYBRID`、`VECTOR_ONLY` 或 `LEXICAL_ONLY`；分数用于本次查询排序，
不是跨查询可比较的概率。

## RAG 黄金问题集评测 `/api/v1/knowledge/evaluations`

```bash
curl -sS -X POST "$BASE_URL/api/v1/knowledge/evaluations" \
  -H 'Content-Type: application/json' \
  -d '{
    "topK":4,
    "minimumRecallAtK":1.0,
    "minimumMeanReciprocalRank":1.0,
    "cases":[
      {
        "name":"产品条款编号",
        "query":"CLAUSE-PROPERTY-001 产品条款",
        "expectedDocumentIds":["CLAUSE-PROPERTY-001"],
        "documentType":"PRODUCT_CLAUSE",
        "productCode":"PROPERTY"
      },
      {
        "name":"暴雨核保规则",
        "query":"RULE-RAIN-001 暴雨洪水核保规则",
        "expectedDocumentIds":["RULE-RAIN-001"],
        "documentType":"UNDERWRITING_RULE",
        "productCode":"PROPERTY"
      }
    ]
  }' | python3 -m json.tool
```

响应中的 `recallAtK` 是各问题召回率的宏平均，`meanReciprocalRank` 衡量首个相关文档的平均倒数排名；
`passed` 只有在两项指标都达到请求阈值时才为 `true`。单次最多 100 条问题，`topK` 范围为 1–20。
预期文档未进入 Top-K 时，对应问题的 `firstRelevantRank` 为 `null`、`reciprocalRank` 为 `0`。
评测本身成功执行时 HTTP 返回 `200`，即使质量阈值未通过也会保留完整逐题报告；CI 或发布脚本必须检查
`passed` 并在 `false` 时阻断发布。`scripts/demo.sh` 已实现这个非零退出门禁。

## 提示词 `/api/v1/prompts`

```bash
curl -sS "$BASE_URL/api/v1/prompts" | python3 -m json.tool
curl -sS "$BASE_URL/api/v1/prompts/underwriting-analysis" | python3 -m json.tool
```

创建和激活版本：

```bash
curl -sS -X POST "$BASE_URL/api/v1/prompts/demo-summary/versions" \
  -H 'Content-Type: application/json' \
  -d '{"body":"问题：{{question}}","requiredVariables":["question"]}' \
  | python3 -m json.tool

curl -sS -X POST "$BASE_URL/api/v1/prompts/demo-summary/versions/1/activate" \
  | python3 -m json.tool
```

预览：

```bash
curl -sS -X POST "$BASE_URL/api/v1/prompts/demo-summary/preview" \
  -H 'Content-Type: application/json' \
  -d '{"variables":{"question":"是否承保"}}' \
  | python3 -m json.tool
```

## 工具调试 `/api/v1/tools`

```bash
curl -sS "$BASE_URL/api/v1/tools" | python3 -m json.tool
curl -sS -X POST "$BASE_URL/api/v1/tools/GET_POLICY/invoke" \
  -H 'Content-Type: application/json' \
  -d '{"policyNo":"P-1001"}' | python3 -m json.tool
curl -sS -X POST "$BASE_URL/api/v1/tools/VALIDATE_RULES/invoke" \
  -H 'Content-Type: application/json' \
  -d '{"policyNo":"P-1001"}' | python3 -m json.tool
```

## MCP Streamable HTTP `/mcp`

初始化请求：

```bash
curl -i -sS -X POST "$BASE_URL/mcp" \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' \
  -d '{
    "jsonrpc":"2.0",
    "id":1,
    "method":"initialize",
    "params":{
      "protocolVersion":"2025-06-18",
      "capabilities":{},
      "clientInfo":{"name":"curl-interview-demo","version":"1.0.0"}
    }
  }'
```

真实 MCP 客户端会保存初始化响应中的 `Mcp-Session-Id`，发送 `notifications/initialized`，然后调用 `tools/list` 或 `tools/call`。服务器暴露：

```text
get_policy
get_quotation
get_underwriting_history
get_survey_report
get_disaster_risk
validate_rules
```

推荐用支持 Streamable HTTP 的 MCP Inspector/SDK 连接 `http://localhost:8080/mcp`，避免手工处理会话头和 SSE 帧。

## 统一错误格式

```bash
curl -sS "$BASE_URL/api/v1/sessions/SES-MISSING" \
  -H 'X-Trace-Id: demo-error-001' | python3 -m json.tool
```

响应包含标准 Problem Details 字段以及：

```json
{
  "status": 404,
  "errorCode": "SESSION_NOT_FOUND",
  "traceId": "demo-error-001",
  "timestamp": "2026-07-13T00:00:00Z"
}
```
