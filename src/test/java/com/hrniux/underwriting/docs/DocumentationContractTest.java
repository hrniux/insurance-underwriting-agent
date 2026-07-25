package com.hrniux.underwriting.docs;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

class DocumentationContractTest {

    private static final Path ROOT = Path.of("").toAbsolutePath();

    @Test
    void shipsAllOperatorAndInterviewDeliverables() throws IOException {
        List<String> files = List.of(
                "README.md",
                "docs/ARCHITECTURE.md",
                "docs/API_EXAMPLES.md",
                "docs/DEMO_DATA_GUIDE.md",
                "docs/INTERVIEW_GUIDE.md",
                "scripts/demo.sh",
                "scripts/knowledge-lifecycle-demo.sh",
                "src/main/resources/static/demo/index.html",
                "src/main/resources/static/demo/app.js",
                "src/main/resources/static/demo/styles.css",
                "src/main/resources/application-persistent-demo.yml",
                "src/main/resources/db/persistent-demo-schema.sql",
                "Dockerfile",
                ".dockerignore");

        assertThat(files).allSatisfy(file -> assertThat(ROOT.resolve(file)).exists().isRegularFile());
    }

    @Test
    void readmeCoversStartupCoreCapabilitiesScenariosAndEvolution() throws IOException {
        String readme = read("README.md");

        assertThat(readme).contains(
                "Java 21",
                "无需 API Key",
                "模型切换",
                "RAG",
                "MCP",
                "P-1001",
                "P-2001",
                "P-3001",
                "P-4001",
                "十分钟学习路线",
                "docs/DEMO_DATA_GUIDE.md",
                "/api/v1/demo/scenarios",
                "/demo/",
                "中文智能核保演示台",
                "生产化演进",
                "mvn clean verify",
                "mvn spring-boot:run",
                "bash scripts/demo.sh");
    }

    @Test
    void apiExamplesCoverEveryPublicEndpointGroup() throws IOException {
        String examples = read("docs/API_EXAMPLES.md");

        assertThat(examples).contains(
                "/api/v1/sessions",
                "/api/v1/underwriting/evaluations",
                "/api/v1/underwriting/evaluations/${evaluation_id}/review",
                "/api/v1/underwriting/reviews",
                "/api/v1/knowledge/documents",
                "/api/v1/knowledge/search",
                "/api/v1/knowledge/evaluations",
                "/api/v1/prompts",
                "/api/v1/tools",
                "/api/v1/demo/scenarios",
                "/actuator/health",
                "/v3/api-docs",
                "/swagger-ui/index.html",
                "/mcp");
    }

    @Test
    void interviewGuideProvidesAFiveMinuteFlowAndAtLeastTenQuestions() throws IOException {
        String guide = read("docs/INTERVIEW_GUIDE.md");

        assertThat(guide).contains("五分钟演示流程", "常见面试问题");
        long questionCount = guide.lines().filter(line -> line.matches("### Q\\d+.*")).count();
        assertThat(questionCount).isGreaterThanOrEqualTo(10);
    }

    @Test
    void teachingGuideExplainsAllScenariosFieldsEnumsAndTheFictionalBoundary() throws IOException {
        String guide = read("docs/DEMO_DATA_GUIDE.md");

        assertThat(guide).contains(
                "文档目的",
                "适用对象",
                "十分钟学习路线",
                "字段字典",
                "枚举对照",
                "如何修改或新增场景",
                "虚构数据",
                "P-1001",
                "P-2001",
                "P-3001",
                "P-4001",
                "RED_RAINSTORM",
                "CRITICAL_FIRE_DEFECT");
    }

    @Test
    void architectureAndDemoScriptDescribeTheSharedScenarioSourceAndChineseFlow() throws IOException {
        assertThat(read("docs/ARCHITECTURE.md")).contains(
                "DemoScenarioRepository",
                "underwriting-scenarios.json",
                "/api/v1/demo/scenarios");

        assertThat(read("scripts/demo.sh")).contains(
                "服务健康检查",
                "演示场景目录",
                "RAG 混合知识检索与分数解释",
                "共享业务工具",
                "人工复核反馈闭环",
                "${title}（${policy_no}）",
                "P-1001",
                "P-2001",
                "P-3001",
                "P-4001");
        assertThat(ROOT.resolve("scripts/demo.sh")).isExecutable();
    }

    @Test
    void documentsTheInteractiveDemoConsoleAndItsFictionalDataBoundary() throws IOException {
        assertThat(read("README.md")).contains(
                "http://localhost:8080/demo/",
                "中文智能核保演示台",
                "虚构数据");
        assertThat(read("docs/DEMO_DATA_GUIDE.md")).contains(
                "浏览器交互演示",
                "选择演示场景",
                "运行智能核保",
                "规则命中",
                "七步");
        assertThat(read("docs/ARCHITECTURE.md")).contains(
                "/demo/",
                "静态演示层",
                "/api/v1/underwriting/evaluations");
    }

    @Test
    void documentsTheRealFourScenarioComparisonFlow() throws IOException {
        assertThat(read("README.md")).contains(
                "对比全部场景",
                "自动通过 1、人工复核 2、拒保 1");
        assertThat(read("docs/DEMO_DATA_GUIDE.md")).contains(
                "横向对比",
                "风险分范围",
                "部分场景");
        assertThat(read("docs/INTERVIEW_GUIDE.md")).contains(
                "浏览器演示台",
                "对比全部场景",
                "实际结论与预期一致");
    }

    @Test
    void documentsTheChineseMarkdownReportFlow() throws IOException {
        assertThat(read("README.md")).contains(
                "下载中文 Markdown 报告",
                "不会重新执行模型或规则");
        assertThat(read("docs/API_EXAMPLES.md")).contains(
                "/{evaluationId}/report",
                "Content-Disposition",
                ".md");
        assertThat(read("docs/DEMO_DATA_GUIDE.md")).contains(
                "P-1001 报告阅读顺序",
                "不会重新执行核保");
        assertThat(read("docs/INTERVIEW_GUIDE.md")).contains(
                "可审计交付物",
                "GET /api/v1/underwriting/evaluations/{evaluationId}/report");
    }

    @Test
    void documentsIdempotentSubmissionAndOperationalMetrics() throws IOException {
        assertThat(read("README.md")).contains(
                "Idempotency-Key",
                "Idempotency-Replayed",
                "IDEMPOTENCY_KEY_CONFLICT",
                "underwriting.evaluation.submissions");
        assertThat(read("docs/ARCHITECTURE.md")).contains(
                "CompletableFuture",
                "underwriting.evaluation.duration",
                "risk_level");
        assertThat(read("docs/API_EXAMPLES.md")).contains(
                "outcome=created|replayed|conflict|failed");
        assertThat(read("scripts/demo.sh")).contains(
                "Idempotency-Key: demo-evaluation-${policy_no}");
    }

    @Test
    void documentsAndExposesTheSafeDegradationDemo() throws IOException {
        assertThat(read("README.md")).contains(
                "degraded-demo",
                "NON_CRITICAL_TOOL_UNAVAILABLE",
                "underwriting.agent.degradations");
        assertThat(read("docs/ARCHITECTURE.md")).contains(
                "ToolCriticality",
                "ToolAttempt",
                "HazardLevel.UNKNOWN",
                "DegradationNotice");
        assertThat(read("docs/API_EXAMPLES.md")).contains(
                "BUSINESS_DATA_COLLECTION",
                "DEGRADED");
        assertThat(read("docs/INTERVIEW_GUIDE.md")).contains(
                "CRITICAL",
                "DEGRADABLE");
        assertThat(read("docs/DEMO_DATA_GUIDE.md")).contains(
                "UNKNOWN",
                "安全降级状态");
    }

    @Test
    void documentsTheImmutableHumanReviewFeedbackLoop() throws IOException {
        assertThat(read("README.md")).contains(
                "人工复核反馈闭环",
                "HUMAN_REVIEW_ALREADY_EXISTS",
                "underwriting.human.reviews");
        assertThat(read("docs/ARCHITECTURE.md")).contains(
                "HumanReviewRepository",
                "putIfAbsent",
                "RESOLVED_MANUAL_REVIEW",
                "underwriting.human.review.delay");
        assertThat(read("docs/API_EXAMPLES.md")).contains(
                "/{evaluationId}/review",
                "/api/v1/underwriting/reviews",
                "用途审批");
        assertThat(read("docs/INTERVIEW_GUIDE.md")).contains(
                "HumanReviewService",
                "人工采纳率",
                "电子签名");
        assertThat(read("docs/DEMO_DATA_GUIDE.md")).contains(
                "HumanReviewOutcome",
                "AgentReviewRelationship",
                "为什么人工复核不能再次覆盖");
        assertThat(read("scripts/demo.sh")).contains(
                "[8/11] 人工复核反馈闭环",
                "underwriting.human.reviews");
    }

    @Test
    void documentsTheOptionalRestartPersistentProfileAndItsBoundaries() throws IOException {
        assertThat(read("README.md")).contains(
                "persistent-demo",
                "data/underwriting.mv.db",
                "PERSISTENT_DB_URL",
                "知识库、Prompt 版本、异步任务状态和 `Idempotency-Key` 注册表仍在内存中");
        assertThat(read("docs/ARCHITECTURE.md")).contains(
                "JdbcSessionRepository",
                "JdbcEvaluationRepository",
                "JdbcHumanReviewRepository",
                "persistent-demo-schema.sql",
                "Flyway",
                "Outbox");
        assertThat(read("docs/API_EXAMPLES.md")).contains(
                "SPRING_PROFILES_ACTIVE=persistent-demo",
                "${evaluation_id}/review");
        assertThat(read("docs/INTERVIEW_GUIDE.md")).contains(
                "### Q16",
                "JSON CLOB",
                "跨聚合事务");
        assertThat(read("docs/DEMO_DATA_GUIDE.md")).contains(
                "默认 Profile 重启后内存评估会丢失",
                "H2 文件");
    }

    @Test
    void documentsTheBoundedAsyncTaskLifecycleAndOperationalBoundary() throws IOException {
        assertThat(read("README.md")).contains(
                "/api/v1/underwriting/tasks",
                "PENDING → RUNNING → SUCCEEDED/FAILED",
                "TASK_QUEUE_CAPACITY",
                "TASK_RETENTION");
        assertThat(read("docs/ARCHITECTURE.md")).contains(
                "UnderwritingTaskService",
                "ThreadPoolTaskExecutor",
                "underwriting.task.transitions",
                "TASK_EXECUTION_FAILED");
        assertThat(read("docs/API_EXAMPLES.md")).contains(
                "202 Accepted",
                "failure.errorCode",
                "持久化任务表/工作流引擎");
        assertThat(read("docs/INTERVIEW_GUIDE.md")).contains(
                "### Q17",
                "任务编号、可查询状态、幂等、容量边界、失败快照和指标",
                "租约、心跳、取消、补偿");
        assertThat(read(".env.example")).contains(
                "TASK_CORE_POOL_SIZE",
                "TASK_MAX_ENTRIES");
    }

    @Test
    void documentsExplainableHybridRetrievalAndItsQualityGate() throws IOException {
        assertThat(read("README.md")).contains(
                "65% vector + 35% lexical",
                "Recall@K",
                "MRR",
                "/api/v1/knowledge/evaluations");
        assertThat(read("docs/ARCHITECTURE.md")).contains(
                "BM25",
                "RetrievalMode",
                "不能跨查询解释成概率或置信度",
                "RetrievalEvaluationService");
        assertThat(read("docs/API_EXAMPLES.md")).contains(
                "vectorScore",
                "lexicalScore",
                "matchedTerms",
                "minimumMeanReciprocalRank");
        assertThat(read("docs/INTERVIEW_GUIDE.md")).contains(
                "### Q18",
                "版本化、脱敏、有代表性的标注集",
                "一次 Demo 的 `passed=true`");
        assertThat(read("scripts/demo.sh")).contains(
                "[4/11] RAG 黄金问题集离线评测",
                "/api/v1/knowledge/evaluations",
                "RAG 质量门禁未通过");
    }

    @Test
    void documentsTheVersionedKnowledgePublicationLifecycleAndBoundaries() throws IOException {
        assertThat(read("README.md")).contains(
                "DRAFT → PUBLISHED → RETIRED",
                "scripts/knowledge-lifecycle-demo.sh",
                "完整旧版或");
        assertThat(read("docs/ARCHITECTURE.md")).contains(
                "KnowledgeVersionRepository",
                "AtomicReference<Map<...>>",
                "新索引构建失败",
                "Outbox");
        assertThat(read("docs/API_EXAMPLES.md")).contains(
                "/versions/1/publish",
                "/versions/2/retire",
                "KNOWLEDGE_DRAFT_EXISTS",
                "INVALID_KNOWLEDGE_TRANSITION");
        assertThat(read("docs/INTERVIEW_GUIDE.md")).contains(
                "### Q19",
                "未审核或错误条款",
                "不能把单实例 `synchronized` 当成分布式发布事务");
        assertThat(read("scripts/knowledge-lifecycle-demo.sh")).contains(
                "[1/8] 创建 v1 草稿",
                "[8/8] 验证下线后不可召回",
                "知识版本生命周期演示完成");
    }

    @Test
    void documentsThePromptRuleSetAndKnowledgeDecisionProvenanceSnapshot() throws IOException {
        assertThat(read("README.md")).contains(
                "决策来源快照",
                "PromptSnapshot",
                "规则集 vN/SHA-256",
                "模板 SHA-256",
                "历史记录不可用");
        assertThat(read("docs/ARCHITECTURE.md")).contains(
                "PromptTemplateService.render",
                "RuleSetSnapshot",
                "knowledgeVersion",
                "vectorScore",
                "matchedTerms",
                "不是数字签名");
        assertThat(read("docs/API_EXAMPLES.md")).contains(
                "templateSha256",
                "ruleSet.sha256",
                "retrievalMode",
                "完整渲染 Prompt 只传给模型网关");
        assertThat(read("docs/INTERVIEW_GUIDE.md")).contains(
                "### Q20",
                "哪版 Prompt、规则和知识",
                "只追加审计日志");
        assertThat(read("docs/DEMO_DATA_GUIDE.md")).contains(
                "完整规则集版本/指纹",
                "向量/词法分");
        assertThat(read("scripts/demo.sh")).contains(
                "来源快照验证通过",
                "templateSha256",
                "ruleSet",
                "knowledgeVersion");
    }

    @Test
    void documentsAndDemonstratesDecisionReplayAndStructuredDrift() throws IOException {
        assertThat(read("README.md")).contains(
                "决策重演与漂移分析",
                "/{evaluationId}/replays",
                "STABLE",
                "SOURCE_DRIFT",
                "DECISION_DRIFT",
                "WEAKENED");
        assertThat(read("docs/ARCHITECTURE.md")).contains(
                "DecisionReplayService",
                "DecisionDriftService",
                "replayedFromEvaluationId",
                "underwriting.decision.replays");
        assertThat(read("docs/API_EXAMPLES.md")).contains(
                "/{evaluationId}/replays",
                "/{evaluationId}/drift",
                "EVALUATION_NOT_REPLAY",
                "Idempotency-Replayed: true");
        assertThat(read("docs/INTERVIEW_GUIDE.md")).contains(
                "### Q21",
                "决策重演和普通重试有什么区别",
                "不可变事实快照");
        assertThat(read("docs/DEMO_DATA_GUIDE.md")).contains(
                "决策重演阅读顺序",
                "决策重演为什么不等于历史复现");
        assertThat(read("scripts/demo.sh")).contains(
                "[7/11] 决策重演与漂移分析",
                "/replays",
                "漂移等级 STABLE");
    }

    private String read(String path) throws IOException {
        return Files.readString(ROOT.resolve(path));
    }
}
