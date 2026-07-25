#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
TEMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TEMP_DIR"' EXIT

health_file="$TEMP_DIR/health.json"
healthy=false
for attempt in $(seq 1 30); do
  if curl --silent --show-error --fail "$BASE_URL/actuator/health" >"$health_file" 2>/dev/null; then
    healthy=true
    break
  fi
  sleep 1
done

if [[ "$healthy" != "true" ]]; then
  echo "服务在 30 秒内未就绪：$BASE_URL" >&2
  exit 1
fi

evaluate_scenario() {
  local step="$1"
  local title="$2"
  local policy_no="$3"
  local question="$4"
  local output_file="$TEMP_DIR/${policy_no}.json"

  echo "[$step/11] ${title}（${policy_no}）"
  curl --silent --show-error --fail -X POST "$BASE_URL/api/v1/underwriting/evaluations" \
    -H 'Content-Type: application/json' \
    -H "X-Trace-Id: demo-${policy_no}" \
    -H "Idempotency-Key: demo-evaluation-${policy_no}" \
    -d "{\"policyNo\":\"$policy_no\",\"question\":\"$question\"}" \
    >"$output_file"
  python3 -m json.tool "$output_file"
  python3 - "$output_file" <<'PY'
import json, re, sys
evaluation = json.load(open(sys.argv[1]))
prompt = evaluation["modelResponse"]["prompt"]
rule_set = evaluation["ruleSet"]
evidence = evaluation["evidence"]
if prompt["code"] != "underwriting-analysis" or prompt["version"] < 1:
    raise SystemExit(f"Prompt 来源快照错误：{prompt}")
if not re.fullmatch(r"[0-9a-f]{64}", prompt["templateSha256"]):
    raise SystemExit("Prompt 模板 SHA-256 格式错误")
if rule_set["version"] != "property-underwriting-default-v1" or rule_set["ruleCount"] != 5:
    raise SystemExit(f"规则集版本快照错误：{rule_set}")
if not re.fullmatch(r"[0-9a-f]{64}", rule_set["sha256"]):
    raise SystemExit("规则集 SHA-256 格式错误")
if not evidence or any(item["knowledgeVersion"] < 1 for item in evidence):
    raise SystemExit("知识证据缺少已发布版本引用")
required = {"vectorScore", "lexicalScore", "retrievalMode", "matchedTerms"}
if any(not required.issubset(item) for item in evidence):
    raise SystemExit("知识证据缺少混合检索解释")
print(
    f"来源快照验证通过：Prompt v{prompt['version']}，规则集 {rule_set['version']}，"
    f"知识版本引用 {len(evidence)} 条"
)
PY
}

echo "[1/11] 服务健康检查"
python3 -m json.tool "$health_file"

echo "[2/11] 演示场景目录"
curl --silent --show-error --fail "$BASE_URL/api/v1/demo/scenarios" >"$TEMP_DIR/scenarios.json"
python3 -m json.tool "$TEMP_DIR/scenarios.json"

echo "[3/11] RAG 混合知识检索与分数解释"
curl --silent --show-error --fail -X POST "$BASE_URL/api/v1/knowledge/search" \
  -H 'Content-Type: application/json' \
  -d '{"query":"仓库暴雨红色风险如何核保","topK":4,"productCode":"PROPERTY"}' \
  >"$TEMP_DIR/search.json"
python3 -m json.tool "$TEMP_DIR/search.json"

echo "[4/11] RAG 黄金问题集离线评测"
curl --silent --show-error --fail -X POST "$BASE_URL/api/v1/knowledge/evaluations" \
  -H 'Content-Type: application/json' \
  -d '{
    "topK":4,
    "minimumRecallAtK":1.0,
    "minimumMeanReciprocalRank":1.0,
    "cases":[
      {"name":"产品条款编号","query":"CLAUSE-PROPERTY-001 产品条款","expectedDocumentIds":["CLAUSE-PROPERTY-001"],"documentType":"PRODUCT_CLAUSE","productCode":"PROPERTY"},
      {"name":"暴雨核保规则","query":"RULE-RAIN-001 暴雨洪水核保规则","expectedDocumentIds":["RULE-RAIN-001"],"documentType":"UNDERWRITING_RULE","productCode":"PROPERTY"}
    ]
  }' >"$TEMP_DIR/retrieval-evaluation.json"
python3 -m json.tool "$TEMP_DIR/retrieval-evaluation.json"
python3 -c '
import json, sys
report = json.load(open(sys.argv[1]))
if not report["passed"]:
    raise SystemExit("RAG 质量门禁未通过")
' "$TEMP_DIR/retrieval-evaluation.json"

echo "[5/11] 共享业务工具"
curl --silent --show-error --fail -X POST "$BASE_URL/api/v1/tools/GET_POLICY/invoke" \
  -H 'Content-Type: application/json' \
  -d '{"policyNo":"P-1001"}' \
  >"$TEMP_DIR/tool.json"
python3 -m json.tool "$TEMP_DIR/tool.json"

evaluate_scenario 6 "高风险仓库：人工复核" "P-1001" "暴雨风险较高，这张仓库财产险能否承保？"

evaluation_id=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["id"])' "$TEMP_DIR/P-1001.json")

echo "[7/11] 决策重演与漂移分析（P-1001）"
curl --silent --show-error --fail -X POST \
  "$BASE_URL/api/v1/underwriting/evaluations/$evaluation_id/replays" \
  -H "Idempotency-Key: demo-replay-${evaluation_id}" \
  >"$TEMP_DIR/replay.json"
python3 -m json.tool "$TEMP_DIR/replay.json"
python3 - "$TEMP_DIR/replay.json" "$evaluation_id" <<'PY'
import json, sys
result = json.load(open(sys.argv[1]))
baseline_id = sys.argv[2]
if result["baseline"]["id"] != baseline_id:
    raise SystemExit("决策重演基线编号不一致")
if result["replay"]["replayedFromEvaluationId"] != baseline_id:
    raise SystemExit("重演评估未保存基线关联")
if result["drift"]["severity"] != "STABLE":
    raise SystemExit(f"默认确定性场景重演应稳定，实际为：{result['drift']['severity']}")
print(f"决策重演验证通过：{baseline_id} -> {result['replay']['id']}，漂移等级 STABLE")
PY

echo "[8/11] 人工复核反馈闭环（P-1001）"
review_url="$BASE_URL/api/v1/underwriting/evaluations/$evaluation_id/review"
if curl --silent --show-error --fail "$review_url" >"$TEMP_DIR/review.json" 2>/dev/null; then
  echo "该幂等评估已有复核记录，直接读取。"
else
  curl --silent --show-error --fail -X POST "$review_url" \
    -H 'Content-Type: application/json' \
    -d '{"reviewerId":"UW-DEMO-001","outcome":"APPROVED","comment":"整改材料已由人工核验，同意附条件承保。","conditions":["提高免赔额至 10 万元","每季度复查排水设施"]}' \
    >"$TEMP_DIR/review.json"
fi
python3 -m json.tool "$TEMP_DIR/review.json"

evaluate_scenario 9 "低风险办公楼：自动通过" "P-2001" "这张低风险办公楼财产险能否承保？"
evaluate_scenario 10 "中风险仓库：人工复核" "P-3001" "这张暴雨风险仓库保单为什么需要人工复核？"
evaluate_scenario 11 "极端火灾厂房：拒保" "P-4001" "这张存在重大消防缺陷的厂房保单能否承保？"

echo "中文演示已完成。"
echo "Swagger：$BASE_URL/swagger-ui/index.html"
echo "场景目录：$BASE_URL/api/v1/demo/scenarios"
echo "教学指南：docs/DEMO_DATA_GUIDE.md"
echo "运行指标：$BASE_URL/actuator/metrics/underwriting.evaluation.submissions"
echo "降级指标：$BASE_URL/actuator/metrics/underwriting.agent.degradations（发生降级后可查询）"
echo "人工复核指标：$BASE_URL/actuator/metrics/underwriting.human.reviews"
echo "决策重演指标：$BASE_URL/actuator/metrics/underwriting.decision.replays"
echo "MCP：$BASE_URL/mcp"
