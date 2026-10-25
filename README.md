# 财险智能核保 Agent Demo

这是一个面向面试讲解和本地学习的 Java 财险智能核保后端。项目用一条完整、可运行、可追踪的链路展示：用户问题理解 → 内部资料查询 → RAG 知识检索 → 风险分析 → 确定性规则校验 → 大模型建议 → 结果持久化。

默认配置完全离线，使用虚构数据、Hash Embedding、内存向量库和确定性 Mock 模型，**无需 API Key**、数据库或外部模型即可启动。所有公司名、地址、条款和保单均为虚构示例，不包含真实业务数据。

## 先跑一次

准备完整 JDK 21 和 Git。仓库自带 Maven Wrapper，无需全局安装 Maven；首次运行需要联网下载 Maven 和依赖。

```bash
git clone https://github.com/hrniux/insurance-underwriting-agent.git
cd insurance-underwriting-agent
./mvnw spring-boot:run
```

启动后打开 <http://localhost:8080/demo/>，选择 `P-2001` 并点击“运行智能核保”：默认场景应返回 `APPROVE`、`LOW`、10 分。展开规则、知识证据和七步轨迹，可查看结论的来源。

完整测试运行 `./mvnw clean verify`；命令行演示 `bash scripts/demo.sh` 还需要 Bash、curl 和 Python 3。

阅读入口：[场景与学习指南](docs/DEMO_DATA_GUIDE.md) · [架构与设计](docs/ARCHITECTURE.md) · [API 示例](docs/API_EXAMPLES.md)。

核心源码：[七步编排](src/main/java/com/hrniux/underwriting/agent/UnderwritingAgentOrchestrator.java) · [确定性规则](src/main/java/com/hrniux/underwriting/rule/UnderwritingRuleEngine.java) · [知识检索与发布](src/main/java/com/hrniux/underwriting/rag/KnowledgeService.java)。

## 技术栈

- Java 21、Maven
- Spring Boot 4.1、Spring MVC、Bean Validation、Actuator
- Spring JDBC、可选 H2 文件数据库 Profile
- Spring AI 2.0 MCP Server，Streamable HTTP `/mcp`
- OpenAI-compatible `/v1/chat/completions` 模型适配器
- springdoc OpenAPI / Swagger UI
- JUnit 5、AssertJ、MockMvc、MockWebServer

## 这个 Demo 能展示什么

- 会话管理：创建/恢复会话，按角色保存用户和助手消息。
- Agent 编排：固定七步流水线，成功或失败都保留步骤轨迹。
- 幂等与并发保护：可选 `Idempotency-Key` 把并发重复提交折叠为一次 Agent 执行，同键异参明确返回冲突。
- 异步任务编排：独立任务 REST API 返回 `202 Accepted`，后台执行七步 Agent，支持状态轮询、同键重放、有界线程池/队列和安全失败快照。
- RAG：Markdown/Text 解析、段落切分、重叠窗口、向量化入库；向量余弦与 BM25 词法分数融合排序，返回命中词/分数组成，并用黄金问题集计算 Recall@K、MRR 和质量阈值。
- 知识发布治理：文档按版本经历 `DRAFT → PUBLISHED → RETIRED`；草稿不可召回，发布新版本原子替换旧索引，下线后立即退出检索并保留历史。
- 业务工具：保单、报价、历史核保、查勘报告、灾害风险和规则校验六类工具，共用统一注册表与审计轨迹。
- 分级故障策略：关键业务资料失败立即终止；灾害外部数据源可安全降级为未知，但强制提升到人工复核并保留结构化告警。
- 规则底线：模型只能解释规则结果，不能把 `MANUAL_REVIEW` 或 `REJECT` 降级为自动通过。
- 模型切换：默认 Mock；也可切到 OpenAI 兼容接口或企业私有化兼容网关，支持连接/读取超时、有限重试和显式降级。
- 提示词治理：版本化、激活版本、变量声明检查和预览渲染。
- 决策来源快照：每次评估固化实际 Prompt 代码/版本/模板 SHA-256、完整规则集版本/指纹，并把知识版本、向量分、词法分、检索模式和命中词写入证据。
- 决策重演与漂移分析：历史评估可用当前事实、知识、Prompt、规则集和模型重新执行；新评估保留基线关联，并结构化比较结论强弱、风险分、规则、证据与来源变化。
- REST + MCP：REST 用于管理和调试，MCP 用于 Agent 工具发现与调用，两者复用同一业务实现。
- 中文交互演示：无需复制命令即可选择四组虚构场景，运行真实核保并阅读规则、证据和七步轨迹。
- 中文核保报告：把已保存的单次评估导出为 Markdown，完整保留结论、规则、证据、七步轨迹、工具记录和模型元数据。
- 服务端复核案件：按复核状态、Agent 建议和保单精确筛选已保存评估，按风险优先稳定分页；换标签页或持久化重启后可直接打开原评估继续查看和复核，无需重新运行 Agent。
- 人工复核闭环：核保人可确认、推翻或要求补充资料；复核记录原子创建、不可覆盖，并进入报告和反馈导出接口。
- 可选重启恢复：`persistent-demo` Profile 把会话、评估和人工复核切换到 H2 文件库，进程重启后仍可查询；默认 Profile 继续使用内存仓储。
- 运行指标：Actuator 暴露评估、降级、人工复核、异步任务和决策重演十组低基数 Micrometer 指标。

## 快速启动

前提：本机有完整 JDK 21+。仓库自带 Maven Wrapper，无需全局安装 Maven；首次运行需要联网下载 Maven 和依赖。

```bash
./mvnw clean verify
./mvnw spring-boot:run
```

修改浏览器演示台后，可用 Node.js 22+ 运行无需额外依赖的行为回归测试；CI 同时执行 Maven 与这组检查：

```bash
node --test src/test/js/*.test.cjs
```

可选的真实浏览器回归需在测试环境安装 Playwright 并先启动本地应用：
`node scripts/test-demo-history.cjs http://localhost:8080`。
已有 Chromium 可通过 `CHROMIUM_EXECUTABLE_PATH` 指定，不影响应用启动依赖。

新开终端检查服务：

```bash
curl --fail http://localhost:8080/actuator/health
bash scripts/demo.sh
```

需要验证客户端重试安全时，可为提交请求增加稳定的 `Idempotency-Key`。首次成功返回 `201` 和
`Idempotency-Replayed: false`；相同键、相同业务载荷再次提交返回同一评估、HTTP `200` 和
`Idempotency-Replayed: true`。相同键绑定不同载荷会返回 `409 IDEMPOTENCY_KEY_CONFLICT`。

```bash
curl -i -X POST http://localhost:8080/api/v1/underwriting/evaluations \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: interview-evaluation-001' \
  -d '{"policyNo":"P-2001","question":"这张办公楼保单是否可以承保？"}'

curl --fail http://localhost:8080/actuator/metrics/underwriting.evaluation.submissions
curl --fail http://localhost:8080/actuator/metrics/underwriting.evaluation.duration
curl --fail http://localhost:8080/actuator/metrics/underwriting.evaluation.decisions
```

幂等记录默认在单实例内保留 24 小时，最多 1,000 条；可通过 `IDEMPOTENCY_RETENTION` 和
`IDEMPOTENCY_MAX_ENTRIES` 调整。失败执行不会占用键，因此调用方可以使用原键重试。

### 异步任务编排

同步评估接口适合交互式请求；如果模型或内部系统响应较慢，可提交异步任务并轮询状态：

```bash
task_json=$(curl -sS --fail-with-body \
  -X POST http://localhost:8080/api/v1/underwriting/tasks \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: interview-task-001' \
  -d '{"policyNo":"P-1001","question":"异步分析这张仓库保单。"}')

task_id=$(printf '%s' "$task_json" \
  | python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])')

curl --fail "http://localhost:8080/api/v1/underwriting/tasks/${task_id}" \
  | python3 -m json.tool
```

首次提交返回 `202 Accepted` 和任务 `Location`；状态按 `PENDING → RUNNING → SUCCEEDED/FAILED`
单向转换。成功任务给出 `evaluationId`，可继续读取评估和报告；失败任务只返回受控 `errorCode/message`，
不会把任意异常堆栈暴露给客户端。相同幂等键和载荷返回原任务及 `Idempotency-Replayed: true`；同键异参
返回 `409`。

执行器默认核心线程 2、最大线程 4、队列 100，任务记录最多保留 1,000 条、完成后保留 24 小时；可通过
`TASK_CORE_POOL_SIZE`、`TASK_MAX_POOL_SIZE`、`TASK_QUEUE_CAPACITY`、`TASK_MAX_ENTRIES` 和
`TASK_RETENTION` 调整。达到执行器或记录容量时明确返回 `503`，不会无界创建线程或堆积任务。

### 安全降级演示

默认启动不会注入故障。需要面试演示内部灾害平台超时时，启用专用 `degraded-demo` Profile：

```bash
SPRING_PROFILES_ACTIVE=degraded-demo ./mvnw spring-boot:run
```

然后在 `/demo/` 运行 `P-2001`，或执行：

```bash
curl -sS -X POST http://localhost:8080/api/v1/underwriting/evaluations \
  -H 'Content-Type: application/json' \
  -d '{"policyNo":"P-2001","question":"灾害平台不可用时是否可以自动承保？"}' \
  | python3 -m json.tool

curl --fail http://localhost:8080/actuator/metrics/underwriting.agent.degradations
```

该 Profile 只模拟 `P-2001` 的灾害工具超时。返回仍保留基础风险 `LOW/10`，但灾害等级使用
`UNKNOWN`，最终决策提升为 `MANUAL_REVIEW`；`degradations` 会返回结构化告警码
`NON_CRITICAL_TOOL_UNAVAILABLE`，失败工具轨迹、`DEGRADED` 步骤、Markdown 报告和
Micrometer 指标也会记录原因。正常 Profile 下四个场景结果完全不变。
演示台会把这类结果标成“安全降级已覆盖常规场景预期”，而不是普通的结果不一致。

### 人工复核反馈闭环

任意单场景评估完成后，演示台会显示“人工复核反馈闭环”。核保人可以记录同意承保、拒绝承保或
要求补充资料。系统不会修改 Agent 原始建议，而是创建一条单独、不可覆盖的 `HumanReview`，并计算
`CONFIRMED`、`OVERRIDDEN`、`RESOLVED_MANUAL_REVIEW` 或 `CONTINUED_MANUAL_REVIEW` 关系。

```bash
curl -sS -X POST \
  http://localhost:8080/api/v1/underwriting/evaluations/EVAL-替换为真实编号/review \
  -H 'Content-Type: application/json' \
  -d '{
    "reviewerId":"UW-DEMO-001",
    "outcome":"APPROVED",
    "comment":"整改材料已核验，同意附条件承保。",
    "conditions":["提高免赔额","季度复查"]
  }' | python3 -m json.tool

curl --fail http://localhost:8080/actuator/metrics/underwriting.human.reviews
```

同一评估第二次提交会返回 `409 HUMAN_REVIEW_ALREADY_EXISTS`，避免覆盖审计结论。重新下载该评估的
Markdown 报告即可看到 Agent 辅助建议与人工最终处理的并列记录。

### 服务端复核案件列表

演示台的“服务端复核案件”从后端读取所有已保存评估，默认显示尚未复核且 Agent 建议人工复核的案件。
可按复核状态、Agent 建议和保单号筛选；结果按风险分降序、创建时间升序、评估编号升序排列，每页 10 条。
点击“打开案件”会读取原评估和已有人工复核，复用详情、报告与重演功能，不会重新执行 Agent。此列表独立于当前标签页的会话记录。

```bash
curl --fail 'http://localhost:8080/api/v1/underwriting/review-worklist?status=UNREVIEWED&decision=MANUAL_REVIEW&page=0&size=10'
```

`UNREVIEWED` 表示尚无人工记录，`INFORMATION_REQUESTED` 表示已要求补充资料，`REVIEWED` 表示人工已承保或拒保。
“要求补充资料”也是不可覆盖的审计记录；资料变化后可用现有重演创建新评估，再记录新的人工处理。
默认内存模式只发现本进程保存的记录；`persistent-demo` 模式可读取重启前的评估和复核。打开历史评估时，
页面会明确区分当前演示场景事实与已保存的原评估。

### 可选文件数据库与重启恢复

默认启动仍是最简单的内存模式，不需要数据库。需要在面试中演示“应用进程重启后评估和人工复核仍存在”时，
启用 `persistent-demo` Profile：

```bash
SPRING_PROFILES_ACTIVE=persistent-demo ./mvnw spring-boot:run
```

首次运行会自动创建 `data/underwriting.mv.db` 及三张表。运行 `bash scripts/demo.sh` 生成评估和人工复核后，
停止应用并使用同一命令重新启动，再读取脚本输出中的评估编号：

```bash
curl --fail \
  http://localhost:8080/api/v1/underwriting/evaluations/EVAL-替换为真实编号 \
  | python3 -m json.tool

curl --fail \
  http://localhost:8080/api/v1/underwriting/evaluations/EVAL-替换为真实编号/review \
  | python3 -m json.tool
```

也可用环境变量指定数据库文件或账号：

```bash
export PERSISTENT_DB_URL='jdbc:h2:file:/tmp/underwriting-demo;DB_CLOSE_ON_EXIT=FALSE'
export PERSISTENT_DB_USERNAME=sa
export PERSISTENT_DB_PASSWORD=''
SPRING_PROFILES_ACTIVE=persistent-demo ./mvnw spring-boot:run
```

这个 Profile 是“可证明跨进程持久化”的本地适配器，不是生产数据库方案。它只持久化会话、评估和人工复核；
知识库、Prompt 版本、异步任务状态和 `Idempotency-Key` 注册表仍在内存中。生产环境应改用 PostgreSQL/Redis、Flyway
版本化迁移、事务/乐观锁、追加式复核事件和多节点幂等，不应直接把 H2 文件放到多实例服务中共享。

浏览器访问：

- 中文智能核保演示台：<http://localhost:8080/demo/>（推荐首次体验）
- Swagger UI：<http://localhost:8080/swagger-ui/index.html>
- OpenAPI JSON：<http://localhost:8080/v3/api-docs>
- 虚构场景目录：<http://localhost:8080/api/v1/demo/scenarios>
- MCP Streamable HTTP：`http://localhost:8080/mcp`

### 浏览器交互演示（推荐）

启动应用后访问 <http://localhost:8080/demo/>，进入“中文智能核保演示台”。

1. 从左侧选择 `P-1001` 至 `P-4001` 任一虚构场景；可按结论筛选，也可使用数字键 `1`–`4` 快速切换；
2. 阅读保单、报价、历史、查勘和灾害五类业务事实，并在“核保任务”中修改问题；
3. 点击“运行智能核保”或按 `Command/Ctrl + Enter`，通过实时状态区观察七步 Agent 编排；
4. 在决策指标区查看端到端耗时、工具可用率、规则命中、知识证据和模型路由；
5. 使用“决策总览、规则与证据、执行与审计、人工复核”四个标签页分层讲解结果；
6. 复制核保摘要、导出完整 JSON，或点击“下载中文 Markdown 报告”获取由后端已保存结果生成的交付物；
7. 点击“重演当前决策”，用当前五类来源重新执行并查看 `STABLE`、`SOURCE_DRIFT` 或 `DECISION_DRIFT` 解释；
8. 在“人工复核反馈闭环”中记录核保人的最终处理，观察它如何与 Agent 建议并列保留；
9. 从“本次会话记录”恢复最近的核保结果，不必重复调用后端；页面刷新前记录保存在浏览器会话存储中；
10. 点击“对比全部场景”，让四组虚构保单顺序调用同一个真实核保接口；
11. 从对比总览确认自动通过 1、人工复核 2、拒保 1，风险分范围为 10–70，再进入任一场景查看五类事实。

页面数据全部来自 `underwriting-scenarios.json`，仅用于教学和面试演示，不可用于真实承保判断。页面不复制业务判断，而是调用与 API 相同的核保服务。

横向对比不会读取预计算静态结论：四张卡片都来自实时 `POST /api/v1/underwriting/evaluations`。单组请求失败时，页面会保留其他成功结果并显示独立失败卡片。

下载链接只会在单场景评估成功后出现。后端读取该评估编号对应的已保存结果来生成报告，不会重新执行模型或规则；切换场景时旧链接会立即清理，避免下载错报告。

顶部服务状态会调用 Actuator 健康检查并显示浏览器观察到的响应耗时。实时七步状态在请求进行期间明确标记为页面等待进度，服务端返回后会由真实 `stepTraces` 状态与耗时覆盖，避免把前端动画误解成后端流式事件。

工作台不依赖外部字体、图标或前端框架，支持浅色/深色主题、键盘操作、移动端布局和减少动态效果偏好。主题写入浏览器本地存储；评估历史只写入当前标签页的会话存储，不上传额外数据。

本次会话记录是本地快捷入口。恢复时会重新读取服务端原评估和当前人工复核，避免另一标签页已复核后仍显示过期表单；选择历史案件也会取消页面上正在等待的评估结果。默认内存模式重启后，已消失的服务端评估无法从本地历史恢复；页面会明确显示读取失败，并清除旧的复核表单和报告入口。需要跨重启恢复时请使用 `persistent-demo`。

## 十分钟学习路线

1. 运行 `./mvnw clean verify`，确认环境和全部测试正常。
2. 运行 `./mvnw spring-boot:run` 启动离线服务。
3. 打开 `/demo/`，先点击“对比全部场景”理解结论分布，再进入一组场景运行详细核保。
4. 打开 `/api/v1/demo/scenarios` 对照页面数据，再运行 `bash scripts/demo.sh` 观察批量 API 流程。
5. 对照 [虚构核保数据与十分钟学习指南](docs/DEMO_DATA_GUIDE.md)，从业务事实追踪到规则命中、风险加分和最终决策。

## 四个教学演示场景

| 保单号 | 场景 | 关键事实 | 确定性结果 |
|---|---|---|---|
| `P-1001` | 临港高风险物流仓库 | 保额 2,000 万元、近三年 2 次出险、排水整改未完成、暴雨红色风险 | 人工复核（`MANUAL_REVIEW`）、高风险（`HIGH`）、70 分 |
| `P-2001` | 低风险科技办公楼 | 保额 500 万元、无历史出险、无查勘遗留、灾害风险低 | 证据齐全时自动通过（`APPROVE`）、低风险（`LOW`）、10 分 |
| `P-3001` | 暴雨暴露商贸仓库 | 保额 1,200 万元、1 次出险、整改完成、暴雨红色风险 | 人工复核（`MANUAL_REVIEW`）、中风险（`MEDIUM`）、40 分 |
| `P-4001` | 极端火灾风险制造厂房 | 保额 800 万元、消防重大缺陷、火灾风险极端 | 拒保（`REJECT`）、高风险（`HIGH`）、60 分 |

知识证据缺失时，即使规则原本允许自动通过，系统也会把决策下限提升到 `MANUAL_REVIEW`，避免无依据自动承保。

## 七步编排

```text
QUESTION_UNDERSTANDING
  -> BUSINESS_DATA_COLLECTION
  -> KNOWLEDGE_RETRIEVAL
  -> RISK_ANALYSIS
  -> RULE_VALIDATION
  -> RECOMMENDATION_GENERATION
  -> RESULT_PERSISTENCE
```

一次完整评估会返回：最终决策、风险等级/分数、命中规则、知识证据、模型摘要、建议动作、结构化降级告警、5 次业务资料工具调用 + 1 次规则工具调用，以及 7 条步骤轨迹。正常流程步骤为 `SUCCESS`；允许继续但资料不完整时明确标记 `DEGRADED`，不会伪装成成功或低风险。

## 决策来源快照

Agent 在一次原子 Prompt 渲染中同时取得渲染结果和 `PromptSnapshot`，因此模型响应保存的 Prompt 代码、激活版本与
模板 SHA-256 一定对应实际送入模型的那一版模板。渲染后的完整 Prompt 含虚构保单事实，只在本次模型调用内使用，
不会写入评估、报告或 API 响应；审计保存的是不含业务正文的版本引用和指纹。

每条 `Evidence` 同时保留知识文档版本、分块编号、融合分、向量分、词法分、`RetrievalMode` 和命中词。浏览器演示台
新增“决策来源快照”，中文 Markdown 报告输出完整模板指纹与知识检索解释。因此可以沿着
“评估 → Prompt vN → 规则集 vN/SHA-256 → 模型路由 → 知识文档 vN / chunk → 规则与工具轨迹”回看当时依据。
规则集指纹来自基础分和全部五条规则的规范化清单，不只包含本次命中的规则。旧 H2 JSON 缺少新增字段时仍可读取，
但明确显示为“历史记录不可用”，不会猜测或伪造来源。

## 决策重演与漂移分析

`POST /api/v1/underwriting/evaluations/{evaluationId}/replays` 不会修改原评估，而是在隔离的新会话中用当前五类来源
重新执行七步 Agent。重演评估保存 `replayedFromEvaluationId`，调用方可用 `Idempotency-Key` 防止网络重试制造重复
执行。响应同时返回基线、重演评估和 `DecisionDrift`：

- `decisionChange` 区分 `UNCHANGED`、`STRENGTHENED` 和 `WEAKENED`；
- 比较风险等级/分数、增加或移除的规则代码；
- 比较新增/移除的知识版本与分块，以及共同证据的检索分数/解释变化；
- 比较 Prompt、完整规则集和模型路由快照；
- 最终归类为 `STABLE`、`SOURCE_DRIFT` 或 `DECISION_DRIFT`，并生成中文解释。

```bash
baseline_id='EVAL-替换为真实编号'
curl -sS -X POST \
  "http://localhost:8080/api/v1/underwriting/evaluations/${baseline_id}/replays" \
  -H "Idempotency-Key: replay-${baseline_id}" \
  | python3 -m json.tool
```

对已经保存的重演评估，可通过 `GET /api/v1/underwriting/evaluations/{replayEvaluationId}/drift` 随时重建对比。这个能力
表达的是“用当前依赖重新评估后发生了什么变化”，不是承诺逐字复现历史模型输出；生产环境还需要不可变业务事实快照、
可寻址模型版本、规则/知识审批链和只追加审计存储。

## 知识版本与发布生命周期

管理 API 不再让外部上传内容立即进入 Agent 检索。`POST /api/v1/knowledge/documents` 创建 v1 草稿；
`POST /documents/{id}/versions/{version}/publish` 才解析、切分、Embedding 并发布。每个文档同时最多一个草稿；
发布 v2 会把 v1 标记为 `RETIRED`，并通过不可变索引快照一次性替换该文档全部分块，搜索线程只会看到完整旧版或
完整新版。`retire` 会移除当前索引，但版本内容和时间戳仍可查询。

发布先完整构建新索引；若解析或 Embedding 失败，旧发布版本和旧索引保持不变，新版本继续为 `DRAFT`。
四个 classpath 演示文档在启动时走同一服务创建并发布，因此默认 Agent 仍可直接运行。执行完整八步生命周期演示：

```bash
bash scripts/knowledge-lifecycle-demo.sh
```

该脚本会真实验证“草稿不可检索 → v1 发布 → v2 草稿不影响 v1 → v2 原子替换 → v2 下线不可检索”。
详细 REST 命令见 [API_EXAMPLES.md](docs/API_EXAMPLES.md)。

## 可解释混合检索与离线评测

`POST /api/v1/knowledge/search` 同时计算向量余弦分数和 BM25 词法分数，并按固定权重
`65% vector + 35% lexical` 融合。词法分析会规范化英文/数字标识符，并为连续中文生成单字和双字词，
因此 `RULE-RAIN-001`、条款编号和“暴雨红色预警”等精确术语即使在 Embedding 信号弱时仍可召回。
每个命中项返回 `score`、`vectorScore`、`lexicalScore`、`mode` 和 `matchedTerms`，便于解释为什么召回。

`POST /api/v1/knowledge/evaluations` 接收最多 100 条黄金问题，按预期文档编号计算平均
`Recall@K` 与 `MRR`，并根据调用方提交的最低阈值返回 `passed`。`bash scripts/demo.sh` 会实际执行两条
黄金问题并展示质量门禁；完整请求见 [API_EXAMPLES.md](docs/API_EXAMPLES.md)。这是一套确定性的教学评测，生产环境
还需要版本化标注集、真实用户问题、NDCG、权限过滤和独立重排评测。

## 模型切换

默认值 `LLM_PROVIDER=mock`，不访问网络。连接 OpenAI 兼容或企业私有化接口时：

```bash
export LLM_PROVIDER=openai-compatible
export LLM_BASE_URL=https://your-model-gateway.example.com
export LLM_API_KEY=replace-with-runtime-secret
export LLM_MODEL=your-model-name
export LLM_CONNECT_TIMEOUT=2s
export LLM_READ_TIMEOUT=15s
export LLM_MAX_ATTEMPTS=3
export LLM_RETRY_BACKOFF=100ms
export LLM_FALLBACK_TO_MOCK=false
./mvnw spring-boot:run
```

只对 I/O 异常、超时、HTTP 429 和 5xx 重试；普通 4xx 不重试。默认禁止静默降级。只有显式设置 `LLM_FALLBACK_TO_MOCK=true` 才回退，并在响应中标记 `fallbackUsed=true`。API Key 只从运行时环境读取，不写日志、不写响应、不写异常。

## Docker

```bash
docker build -t insurance-underwriting-agent-demo:local .
docker run --rm -p 8080:8080 insurance-underwriting-agent-demo:local
```

容器使用 Java 21 运行时、非 root 用户和健康检查。默认仍为无外部依赖的 Mock 模式。

## 项目结构

```text
src/main/java/com/hrniux/underwriting
├── agent      # 七步编排、评估结果、决策重演/漂移与步骤轨迹
├── api        # REST 控制器和请求 DTO
├── demo       # 结构化虚构场景、完整性校验和中文目录 API
├── model      # Mock/OpenAI-compatible/路由模型网关
├── prompt     # 提示词版本管理、严格渲染和不可泄露的版本指纹快照
├── rag        # 知识版本发布、原子索引、Embedding、混合检索与离线评测
├── report     # 中文 Markdown 核保报告与安全转义
├── review     # 人工复核结果、关系分类、原子仓库与反馈指标
├── rule       # 确定性规则、风险分与决策下限
├── session    # 会话和消息
├── task       # 异步任务状态机、有界执行器与任务提交幂等
├── tool       # 虚构内部系统、注册表、MCP 工具
└── shared     # 配置、统一异常和持久化 JSON 编解码
```

进一步阅读：

- [虚构核保数据与十分钟学习指南](docs/DEMO_DATA_GUIDE.md)
- [架构与关键设计](docs/ARCHITECTURE.md)
- [完整 API / MCP 示例](docs/API_EXAMPLES.md)
- [面试演示与追问答案](docs/INTERVIEW_GUIDE.md)

## 生产化演进

这个项目有意把端口与内存/JDBC 实现分开，便于说明生产改造路线：

| Demo 实现 | 生产替换方向 |
|---|---|
| 默认内存仓储；可选 H2 聚合 JSON 仓储 | Redis 会话 + PostgreSQL 结构化审计与评估表、Flyway |
| H2 唯一键保护的单条不可覆盖复核 | PostgreSQL 追加式复核事件、RBAC、电子签名与事务 |
| Hash Embedding | 企业 Embedding 模型或合规云模型 |
| 内存向量 + BM25 混合检索 | PostgreSQL + PGVector、Milvus 或 Elasticsearch/OpenSearch 混合检索与重排 |
| JSON 场景仓库 + 分级失败工具 | 带超时/熔断/舱壁和明确异常分类的内部 REST/gRPC/MQ 适配器 |
| 单机有界异步任务 + 单实例幂等 | 持久化状态机/工作流引擎、消息队列、跨节点幂等、超时取消与补偿 |
| 环境变量密钥 | Vault/KMS、短期凭证、密钥轮转和出口网关 |
| Micrometer 单实例指标 | Prometheus Registry、OpenTelemetry Trace、模型成本与召回质量看板 |

生产环境还应加入租户/机构权限、字段级脱敏、操作审计、知识发布审批、Prompt 灰度、脱敏反馈样本治理、模型输出评测和灾备策略。

## 免责声明

本项目仅用于技术学习和面试演示，不构成真实保险核保意见。示例数据、规则阈值和条款均为虚构内容。
