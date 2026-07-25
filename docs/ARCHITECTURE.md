# 架构与关键设计

## 1. 目标与边界

这个 Demo 解决的不是“让模型替核保人拍板”，而是把分散资料聚合、知识查找、规则检查和建议生成串成可审计的辅助决策闭环。确定性规则拥有最终决策下限；模型负责组织语言和给出后续动作。

所有业务数据、条款和地址均为虚构。默认实现强调本地可运行和面试可解释，不声称具备生产级语义召回效果。

## 2. 组件关系

```mermaid
flowchart LR
    User["REST / MCP 调用方"] --> API["Spring MVC API"]
    User --> MCP["Spring AI MCP /mcp"]
    User --> DemoAPI["场景目录 /api/v1/demo/scenarios"]
    DemoUser["浏览器演示用户"] --> Console["静态演示层 /demo/"]
    Console --> DemoAPI
    Console --> EvaluationAPI["核保评估 /api/v1/underwriting/evaluations"]
    Console --> ReviewAPI["人工复核 /evaluations/{id}/review"]
    API --> Agent["UnderwritingAgentOrchestrator"]
    EvaluationAPI --> Submission["EvaluationSubmissionService"]
    TaskAPI["异步任务 /api/v1/underwriting/tasks"] --> TaskService["UnderwritingTaskService"]
    TaskService --> TaskRepository["UnderwritingTaskRepository"]
    TaskService --> TaskExecutor["有界 ThreadPoolTaskExecutor"]
    TaskExecutor --> Submission
    Submission --> Agent
    Submission --> Metrics["Micrometer metrics"]
    Submission --> Idempotency["Bounded idempotency registry"]
    Agent --> Session["SessionService"]
    Agent --> Tools["ToolRegistry"]
    MCP --> Tools
    Data["underwriting-scenarios.json"] --> Scenarios["DemoScenarioRepository"]
    Scenarios --> Tools
    Scenarios --> DemoAPI
    Agent --> RAG["KnowledgeService"]
    RAG --> Split["Parser + Splitter"]
    RAG --> Vector["Hash Embedding + VectorStore"]
    RAG --> Lexical["中文/标识符词法分析 + BM25"]
    Vector --> Fusion["65/35 融合排序 + 分数解释"]
    Lexical --> Fusion
    EvalAPI["黄金问题集 /knowledge/evaluations"] --> RAGEval["Recall@K + MRR + 质量阈值"]
    RAGEval --> RAG
    Agent --> Rules["UnderwritingRuleEngine"]
    Agent --> Prompt["PromptTemplateService"]
    Agent --> Router["RoutingModelGateway"]
    Router --> Mock["Deterministic Mock"]
    Router --> Compatible["OpenAI-compatible Gateway"]
    Agent --> Evaluation["EvaluationRepository"]
    Session --> SessionRepository["SessionRepository"]
    ReviewAPI --> ReviewService["HumanReviewService"]
    ReviewService --> Evaluation
    ReviewService --> ReviewRepository["HumanReviewRepository"]
    ReviewService --> Metrics
    SessionRepository --> MemoryAdapters["默认：内存适配器"]
    Evaluation --> MemoryAdapters
    ReviewRepository --> MemoryAdapters
    SessionRepository --> JdbcAdapters["persistent-demo：JDBC 适配器"]
    Evaluation --> JdbcAdapters
    ReviewRepository --> JdbcAdapters
    JdbcAdapters --> H2["H2 文件数据库"]
```

### 静态演示层

`/demo/` 由 Spring Boot 直接提供 HTML、原生 JavaScript 和 CSS，不需要 Node.js、外部字体或前端构建。页面先调用 `/api/v1/demo/scenarios` 读取四组虚构场景，用户点击“运行智能核保”后再调用 `/api/v1/underwriting/evaluations`。动态接口内容统一通过安全 DOM API 写入页面，不作为 HTML 字符串执行。

静态演示层只负责把业务事实、结论、规则命中、知识证据和轨迹翻译成更容易理解的中文界面，不复制核保逻辑。场景事实仍来自 `DemoScenarioRepository`，Agent 建议仍由真实七步流程、规则引擎和模型网关共同生成；人工复核表单调用独立 Review API，不在浏览器内修改评估结果。

## 3. 结构化虚构场景

`src/main/resources/demo/underwriting-scenarios.json` 是演示业务事实的唯一来源。`DemoScenarioRepository` 在应用启动时加载四组场景，检查重复保单号、子对象保单号、负金额、日期范围、预期风险分和重复规则代码。无效数据会阻止应用启动，不会静默回退。

`FakeUnderwritingFactTools` 从仓库读取保单、报价、历史核保、查勘和灾害风险五类事实，REST 与 MCP 工具继续复用相同实现。只读场景接口 `/api/v1/demo/scenarios` 也读取同一仓库，但只承担教学展示，不创建会话或触发核保流程。

## 4. 七步 Agent 流程

| 顺序 | 步骤 | 主要动作 | 失败策略 |
|---|---|---|---|
| 1 | `QUESTION_UNDERSTANDING` | 创建/恢复会话，写入用户问题 | 会话不存在则终止 |
| 2 | `BUSINESS_DATA_COLLECTION` | 调用保单、报价、历史、查勘、灾害五个工具 | 关键资料失败则终止；灾害数据源失败可安全降级并标记 `DEGRADED` |
| 3 | `KNOWLEDGE_RETRIEVAL` | 问题 + 标的用途 + 灾害等级组合检索 | 空证据不终止，但提高决策下限 |
| 4 | `RISK_ANALYSIS` | 汇总强类型 `UnderwritingContext` | 类型/数据不完整则终止 |
| 5 | `RULE_VALIDATION` | 执行五条确定性规则 | 规则结论成为模型不可降低的下限 |
| 6 | `RECOMMENDATION_GENERATION` | 渲染 Prompt 并调用路由模型 | 按配置重试/显式降级，否则返回 503 |
| 7 | `RESULT_PERSISTENCE` | 保存评估并追加助手消息 | 失败轨迹保留并传播异常 |

`runStep` 统一测量耗时并写入 `StepTrace`。`ToolRegistry` 同样为每个工具写入脱敏 `ToolCallTrace`，因此可以区分“流程慢”还是“某个内部系统慢”。

### 4.1 决策来源快照

`PromptTemplateService.render` 只读取一次当前激活模板，并在同一个返回值中携带渲染内容和 `PromptSnapshot`：模板代码、
版本、模板正文 SHA-256。编排器把渲染内容交给 `ModelGateway`，但只把不含保单事实的快照随 `ModelResponse` 保存。
这样不会出现“先渲染 v1、记录时却读到刚激活的 v2”的竞态，也不会为了审计长期保存完整模型输入中的业务事实。

RAG 命中转换为评估 `Evidence` 时不再丢弃检索解释，而是保留 `knowledgeVersion`、document/chunk ID、综合分、
`vectorScore`、`lexicalScore`、`RetrievalMode` 和 `matchedTerms`。报告与演示台可以把一次结论追溯到实际 Prompt 版本、
模型路由和具体知识版本。`UnderwritingRuleEngine` 还暴露 `RuleSetSnapshot`：以规则集版本、基础分和全部规则规范化清单
计算 SHA-256，因此即使某条规则本次没有命中，也仍属于当时决策来源。新增字段用可缺省类型兼容旧聚合 JSON；缺失来源
归一化为 `version=0`、`ruleCount=0`、`UNKNOWN` 和 `unavailable`，明确承认历史证据不足，而不是编造来源。

模板 SHA-256 是内容指纹，用于定位配置和发现意外变化，不是数字签名，也不能单独证明数据库未被篡改。生产环境还要
把 Prompt、规则和知识发布事件写入只追加审计存储，并结合操作者身份、审批记录、时间戳签名和留存策略。

### 4.2 决策重演与漂移分析

`DecisionReplayService` 先只读取得基线评估，再创建一个带 `replayedFromEvaluationId` 的 `EvaluationRequest`。编排器在
隔离的新会话中执行完整七步并保存新评估，不修改基线，也不把新消息追加到原会话。重演沿用
`EvaluationSubmissionService` 的单飞幂等状态机；请求指纹包含基线编号，因此同一个键不能被错误复用于其他基线或普通评估。

`DecisionDriftService` 是纯比较服务，不重新调用工具或模型。它对两个已保存评估执行以下确定性比较：

1. 按 `APPROVE < MANUAL_REVIEW < REJECT` 判断结论不变、趋严或趋松；
2. 计算风险分增量、风险等级变化和规则代码集合差；
3. 用 `documentId + knowledgeVersion + chunkId` 对齐证据，找出新增/移除引用及共同引用的检索分数/解释变化；
4. 比较 Prompt 快照、完整规则集快照以及模型 provider/model/fallback；
5. 分类为 `STABLE`、`SOURCE_DRIFT` 或 `DECISION_DRIFT`，并生成可直接展示的中文解释。

```mermaid
flowchart LR
    B["已保存基线评估"] --> R["POST /{id}/replays"]
    R --> A["当前七步 Agent"]
    A --> N["新评估<br/>replayedFromEvaluationId"]
    B --> D["DecisionDriftService"]
    N --> D
    D --> C["结论、规则、证据与来源差异"]
```

`GET /{replayEvaluationId}/drift` 根据持久化的基线关联重新构建对比，不依赖浏览器会话。这里的“重演”是当前状态下的
再评估，不是假装复现不可控的历史模型输出。严格历史复现还需要不可变业务事实快照、可寻址模型部署、版本化工具响应、
规则/知识审批事件和只追加审计日志。

## 5. RAG 流程

外部知识先进入版本化发布状态机，只有 `PUBLISHED` 版本能写入检索索引：

```mermaid
stateDiagram-v2
    [*] --> DRAFT: 创建版本
    DRAFT --> PUBLISHED: 解析/切分/Embedding 成功
    PUBLISHED --> RETIRED: 发布新版本或主动下线
    RETIRED --> [*]
```

同一文档同时最多一个 `DRAFT`。发布 v2 时，服务先完整构建 v2 分块；`VectorStore.replaceDocument` 使用
`AtomicReference<Map<...>>` 交换不可变索引快照，按 `documentId` 一次性删除 v1 分块并加入 v2 分块，检索线程
不会观察到半新半旧的索引。新索引构建失败时不会修改快照，也不会把 v2 标为已发布；v1 继续对 Agent 可见。
状态转换完成后，v1 保留为 `RETIRED` 历史。主动下线会移除索引和当前发布目录，但不删除版本记录。

```text
Markdown/Text
  -> 清理与解析
  -> 段落优先切分
  -> 固定最大窗口 + 重叠
  -> Hash Embedding（固定维度、L2 归一化）
  -> 内存向量库
  -> 中文单字/双字 + 英文数字标识符词法分析
  -> 余弦相似度 + BM25（65/35）融合 Top-K
  -> 文档类型/险种过滤 + 分数组成/命中词解释
```

Hash Embedding 的优点是无需模型、完全确定、测试稳定；缺点是语义能力有限。词法分支对条款编号、规则代码和
风险专有名词提供精确召回，BM25 在完成文档类型/险种过滤后的候选集上计算，并在单次查询内归一化；向量分数与
词法分数分别保留，`RetrievalMode` 标记 `HYBRID`、`VECTOR_ONLY` 或 `LEXICAL_ONLY`。融合分数只用于本次查询排序，
不能跨查询解释成概率或置信度。

`RetrievalEvaluationService` 接受最多 100 条带预期文档编号的黄金问题，复用相同检索入口计算宏平均 Recall@K、
MRR、首个相关文档排名和逐题结果，并用调用方阈值给出 `passed` 质量门禁。Demo 的两条固定示例只能证明计算与
回归链路，不能代表真实保险问题分布。生产环境保持 `EmbeddingService` 与 `VectorStore` 端口不变，替换企业
Embedding + PGVector/Milvus/OpenSearch，并对版本化脱敏标注集增加 NDCG、重排、权限过滤和线上漂移监控。

## 6. 规则优先于模型

五条默认规则分别覆盖红色暴雨、重复出险、高保额、整改未完成和极端火灾叠加重大消防缺陷。风险分从 10 分起算，累加命中影响并限制在 0–100：

- 0–29：`LOW`
- 30–59：`MEDIUM`
- 60–79：`HIGH`
- 80–100：`CRITICAL`

决策强度为 `APPROVE < MANUAL_REVIEW < REJECT`。编排器只从规则结果计算最终决策，模型响应没有修改决策的字段。知识证据为空时额外施加 `MANUAL_REVIEW` 下限。

## 7. 模型网关

`ModelGateway` 是唯一模型端口：

- `DeterministicMockModelGateway`：稳定生成中文摘要，适合离线演示和回归测试。
- `OpenAiCompatibleModelGateway`：调用 `/v1/chat/completions`，设置连接/读取超时，限定尝试次数。
- `RoutingModelGateway`：按配置选择主模型；只在显式允许时回退 Mock。

429、5xx、网络 I/O 和超时可以重试，普通 4xx 立即失败。线程中断会被恢复。密钥不进入 `toString`、异常、日志或响应。

## 8. REST 与 MCP 共用业务逻辑

REST 的 `/api/v1/tools` 和 MCP 的六个 `@McpTool` 都委托给 `ToolRegistry` 与 `UnderwritingRuleEngine`，没有复制内部系统查询规则。这样工具定义、审计和错误语义保持一致。

MCP 使用 Spring AI WebMVC Server 的 Streamable HTTP 传输，默认地址 `/mcp`。六个工具都有强类型参数与输出 Schema，适合作为另一个 Agent 或大模型客户端的工具服务器。

### 8.1 分级工具失败与安全降级

`ToolName` 通过 `ToolCriticality` 显式声明 `CRITICAL` 或 `DEGRADABLE`。`ToolRegistry` 提供两种语义但复用同一套计时、
错误码和脱敏轨迹：

- `invoke` 是严格调用，失败时原样传播异常，适用于保单、报价、历史、查勘和规则校验；
- `tryInvoke` 返回 `ToolAttempt`，同时携带结果或失败以及对应轨迹，供编排器执行明确的降级策略；
- 全局调试轨迹使用有界队列，仅保留最近 1,000 条，避免长期运行无限增长。

灾害平台是当前唯一 `DEGRADABLE` 工具。失败后系统构造 `HazardLevel.UNKNOWN` 占位，不会用
`LOW` 冒充未知；业务资料采集步骤标记为 `DEGRADED`，结果写入 `DegradationNotice`，决策下限
提升到 `MANUAL_REVIEW`。原始规则分和风险等级继续保留，因此调用方能区分“基础风险低”和
“资料不完整所以不能自动承保”。关键工具仍失败即终止，不允许普遍吞错。

`degraded-demo` Profile 只为 `P-2001` 注入灾害平台超时，用于可重复演示和集成测试；默认 Profile
不包含故障注入。生产适配器还应把超时、限流、无数据、权限失败和程序缺陷分类，只有允许的故障
类型才能进入降级路径。

## 9. 异步任务、幂等与指标

同步评估 API 保留给需要立即获取结果的调用方；`POST /api/v1/underwriting/tasks` 则把同一个
`EvaluationSubmissionService` 放入有界执行器，立即返回任务快照和 `Location`。任务状态机只有四个状态：

```mermaid
stateDiagram-v2
    [*] --> PENDING: 接收并保存任务
    PENDING --> RUNNING: 工作线程取得任务
    RUNNING --> SUCCEEDED: 保存 evaluationId
    RUNNING --> FAILED: 保存安全 failure
    SUCCEEDED --> [*]
    FAILED --> [*]
```

`UnderwritingTask` 是不可变 record，并在构造时校验每个状态允许出现的时间戳、评估编号和失败字段，禁止
终态再次转换。默认执行器为 2 个核心线程、最多 4 个线程、100 个排队任务；任务仓库最多保留 1,000 条，
只在新提交时清理超过 24 小时的终态任务。队列或记录容量耗尽时返回 `503`，而不是无界消耗内存和线程。

任务提交拥有独立的幂等槽位：相同键、相同规范化请求重放原任务，不会重复执行 Agent；同键异参返回
`409 IDEMPOTENCY_KEY_CONFLICT`。后台异常被分类为领域错误、模型错误或固定的
`TASK_EXECUTION_FAILED`，API 不返回堆栈和任意内部异常信息。

`POST /api/v1/underwriting/evaluations` 可选接收 `Idempotency-Key`。提交服务对规范化后的
`sessionId + policyNo + question + replayedFromEvaluationId` 计算 SHA-256 指纹，并用一个短临界区原子认领键：

- 首个请求成为 owner，执行一次七步 Agent；
- 同键、同指纹的并发请求等待同一个 `CompletableFuture`，完成后直接重放同一评估；
- 同键、不同指纹立即返回 `409 IDEMPOTENCY_KEY_CONFLICT`；
- 执行失败会完成异常并删除槽位，原键可以安全重试；
- 只淘汰已完成槽位，绝不淘汰进行中的 owner，避免容量回收制造重复执行。

这是单实例 Demo 实现。缓存通过最大条数和完成后保留期限制内存，默认 1,000 条、24 小时。
多实例生产环境应把同一状态机迁移到 Redis 或带唯一约束的数据库，并保存最终响应或评估编号。

评估提交服务写入四组低基数 Micrometer 指标：

| 指标 | 标签 | 含义 |
|---|---|---|
| `underwriting.evaluation.submissions` | `outcome` | `created`、`replayed`、`conflict`、`failed` 提交数 |
| `underwriting.evaluation.duration` | `outcome` | 包含并发等待时间的 API 提交耗时 |
| `underwriting.evaluation.decisions` | `decision`、`risk_level` | 实际执行生成的结论分布，不重复统计重放请求 |
| `underwriting.agent.degradations` | `tool`、`reason` | 实际执行发生的安全降级；重放请求不重复统计 |
| `underwriting.decision.replays` | `severity`、`outcome` | 决策重演的稳定/来源漂移/决策漂移分布，以及创建/幂等重放次数 |
| `underwriting.task.submissions` | `outcome` | `accepted`、`replayed`、`conflict`、`rejected`、`failed` 任务提交数 |
| `underwriting.task.transitions` | `status` | `running`、`succeeded`、`failed` 状态转换数 |
| `underwriting.task.duration` | `outcome` | 后台任务从运行到成功/失败的耗时 |

`/actuator/metrics/{metricName}` 可直接检查本机指标。生产环境再接 Prometheus Registry 和告警系统。

## 10. 人工复核反馈闭环

`POST /api/v1/underwriting/evaluations/{evaluationId}/review` 在 Agent 评估之外创建一条不可变
`HumanReview`。它不会覆盖 `UnderwritingEvaluation.decision`，因此报告可以同时展示“Agent 辅助建议”
和“人工处理结论”，审计时不会丢失模型与规则当时给出的原始判断。

`HumanReviewRepository.create` 使用 `putIfAbsent` 原子保证一份评估最多只有一条当前演示结论；第二次
提交返回 `409 HUMAN_REVIEW_ALREADY_EXISTS`。关系分类不交给前端或模型，而是由服务端确定性计算：

| Agent 建议与人工结果 | `relationship` | 含义 |
|---|---|---|
| 自动通过/拒保与人工结果一致 | `CONFIRMED` | 人工确认 Agent 建议 |
| 自动通过/拒保被人工改变 | `OVERRIDDEN` | 人工推翻 Agent 建议 |
| 人工复核最终变为承保或拒保 | `RESOLVED_MANUAL_REVIEW` | 人工完成处置 |
| 人工复核仍需更多资料 | `CONTINUED_MANUAL_REVIEW` | 继续停留在人工环节 |

复核服务额外暴露两组低基数指标：

| 指标 | 标签 | 含义 |
|---|---|---|
| `underwriting.human.reviews` | `outcome`、`relationship` | 人工结论与采纳/推翻关系分布 |
| `underwriting.human.review.delay` | `outcome`、`relationship` | 从 Agent 评估完成到人工复核的时延 |

标签不包含人员编号、评估编号或保单号。`GET /api/v1/underwriting/reviews` 可作为本地反馈导出入口；
生产环境必须增加机构权限、脱敏、用途审批和数据留存控制，不能直接把原始业务记录送去训练模型。

## 11. 可选持久化 Profile

业务服务只依赖 `SessionRepository`、`EvaluationRepository` 和 `HumanReviewRepository` 端口，具体实现由
Spring Profile 选择：

| 启动方式 | 仓储实现 | 生命周期 | 适合场景 |
|---|---|---|---|
| 默认 Profile | 三个 `InMemory*Repository` | 进程退出即释放 | 最快的离线学习、测试和浏览器演示 |
| `persistent-demo` | 三个 `Jdbc*Repository` | H2 文件保留，进程重启可恢复 | 面试演示持久化边界与适配器替换 |

三个 JDBC 适配器分别是 `JdbcSessionRepository`、`JdbcEvaluationRepository` 和
`JdbcHumanReviewRepository`；切换 Profile 不会改变业务服务和控制器的依赖类型。

`persistent-demo-schema.sql` 创建会话、评估、人工复核三张表。表中保留用于主键、关联、唯一约束和排序的
结构化列，同时把不可变领域聚合序列化为 JSON CLOB；因此新增返回字段时不需要立刻扩充大量 Demo 列，
读取时又能还原完整步骤、证据、工具轨迹和模型元数据。人工复核表以 `evaluation_id` 为主键并引用评估表，
数据库约束和 `DuplicateKeyException` 映射共同保证“首条复核成功，后续返回冲突”。

这个设计刻意控制在教学规模。H2 文件不适合容器多副本共享，聚合 JSON 不适合复杂报表，初始化 SQL 也没有
生产迁移历史；会话、评估和人工复核的多次写入目前不是一个跨聚合事务。生产演进应使用 PostgreSQL JSONB/
结构化字段、Flyway、事务边界、乐观锁或 Outbox，并把更正建模成追加事件。知识库、Prompt、任务和幂等注册表仍是
内存实现，也需要分别迁移到向量数据库、配置中心和 Redis/数据库。

## 12. 一致性与生产演进

默认仓库使用 `ConcurrentHashMap`、不可变 record、复制后返回和单实例幂等单飞；可选 JDBC 仓库增加文件级
重启恢复、外键和唯一键约束。真正生产化还需要：

- 会话版本号、跨节点幂等记录和数据库事务；
- 分布式锁或工作流实例级串行；
- 持久化任务恢复、租约/心跳、超时取消、补偿与死信队列；
- RAG 发布审批人、双人复核、知识生效区间、持久化版本表和数据库/索引跨系统一致性；
- 租户隔离、RBAC、脱敏、全链路审计和数据留存策略；
- Prompt/模型灰度、离线评测集、召回指标和经过审批的脱敏反馈样本集。

## 13. 主要扩展点

| 接口 | 当前实现 | 可替换实现 |
|---|---|---|
| `SessionRepository` | 默认内存 / 可选 H2 JDBC | Redis / PostgreSQL |
| `EvaluationRepository` | 默认内存 / 可选 H2 JDBC | PostgreSQL / 审计仓库 |
| `HumanReviewRepository` | 默认内存 / 可选 H2 唯一键 | PostgreSQL 追加事件 / 工作流任务库 |
| `UnderwritingTaskRepository` | 有界内存任务快照 | 工作流引擎 / PostgreSQL 任务表与租约 |
| `EmbeddingService` | Hash | 企业 Embedding API |
| `KnowledgeVersionRepository` | 内存版本历史与三态发布 | PostgreSQL 版本表、审批流和 Outbox |
| `VectorStore` | 不可变内存快照、按文档原子替换 | PGVector / Milvus / OpenSearch 索引别名切换 |
| `UnderwritingFactTools` | JSON 场景仓库 | 内部 REST/gRPC/MQ 适配器 |
| `ModelGateway` | Mock / OpenAI-compatible | 企业模型平台 SDK |
