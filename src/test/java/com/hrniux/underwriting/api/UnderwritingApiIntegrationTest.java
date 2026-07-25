package com.hrniux.underwriting.api;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThan;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.jayway.jsonpath.JsonPath;

@SpringBootTest
class UnderwritingApiIntegrationTest {

    @Autowired
    private WebApplicationContext context;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @Test
    void createsAndReadsASessionThenEvaluatesAndReadsTheResult() throws Exception {
        String sessionJson = mvc.perform(post("/api/v1/sessions"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        String sessionId = JsonPath.read(sessionJson, "$.id");

        mvc.perform(get("/api/v1/sessions/{id}", sessionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(sessionId));

        String evaluationJson = mvc.perform(post("/api/v1/underwriting/evaluations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sessionId":"%s","policyNo":"P-1001","question":"这张仓库财产险能否承保？"}
                                """.formatted(sessionId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.decision").value("MANUAL_REVIEW"))
                .andExpect(jsonPath("$.evidence").isNotEmpty())
                .andExpect(jsonPath("$.evidence[0].knowledgeVersion").value(greaterThan(0)))
                .andExpect(jsonPath("$.evidence[0].vectorScore").isNumber())
                .andExpect(jsonPath("$.evidence[0].lexicalScore").isNumber())
                .andExpect(jsonPath("$.evidence[0].retrievalMode").isNotEmpty())
                .andExpect(jsonPath("$.evidence[0].matchedTerms").isArray())
                .andExpect(jsonPath("$.modelResponse.prompt.code").value("underwriting-analysis"))
                .andExpect(jsonPath("$.modelResponse.prompt.version").value(greaterThan(0)))
                .andExpect(jsonPath("$.modelResponse.prompt.templateSha256").isNotEmpty())
                .andExpect(jsonPath("$.ruleSet.version").value("property-underwriting-default-v1"))
                .andExpect(jsonPath("$.ruleSet.ruleCount").value(5))
                .andExpect(jsonPath("$.ruleSet.sha256").isNotEmpty())
                .andExpect(jsonPath("$.replayedFromEvaluationId").doesNotExist())
                .andExpect(jsonPath("$.toolTraces.length()").value(6))
                .andExpect(jsonPath("$.stepTraces.length()").value(7))
                .andReturn().getResponse().getContentAsString();
        String evaluationId = JsonPath.read(evaluationJson, "$.id");

        mvc.perform(get("/api/v1/underwriting/evaluations/{id}", evaluationId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(evaluationId));
    }

    @Test
    void downloadsAChineseMarkdownReportForASavedEvaluation() throws Exception {
        String evaluationJson = mvc.perform(post("/api/v1/underwriting/evaluations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"policyNo":"P-1001","question":"这张保单是否承保 | 需要说明？\\n第二行"}
                                """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String evaluationId = JsonPath.read(evaluationJson, "$.id");

        mvc.perform(get("/api/v1/underwriting/evaluations/{id}/report", evaluationId)
                        .accept("text/markdown"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, containsString("text/markdown")))
                .andExpect(header().string(
                        HttpHeaders.CONTENT_DISPOSITION,
                        containsString("underwriting-report-" + evaluationId + ".md")))
                .andExpect(content().string(containsString("# 财险智能核保评估报告")))
                .andExpect(content().string(containsString("人工复核（`MANUAL_REVIEW`）")))
                .andExpect(content().string(containsString("`RED_RAINSTORM`")))
                .andExpect(content().string(containsString("知识版本")))
                .andExpect(content().string(containsString("提示词版本")))
                .andExpect(content().string(containsString("模板 SHA-256")))
                .andExpect(content().string(containsString("规则集版本")))
                .andExpect(content().string(containsString("规则集 SHA-256")))
                .andExpect(content().string(containsString("本报告仅用于技术学习和面试演示")));
    }

    @Test
    void returnsTheConsistentNotFoundProblemWhenAReportEvaluationDoesNotExist() throws Exception {
        mvc.perform(get("/api/v1/underwriting/evaluations/EVAL-MISSING/report"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.errorCode").value("EVALUATION_NOT_FOUND"))
                .andExpect(jsonPath("$.instance")
                        .value("/api/v1/underwriting/evaluations/EVAL-MISSING/report"));
    }

    @Test
    void returnsAConsistentValidationProblem() throws Exception {
        mvc.perform(post("/api/v1/underwriting/evaluations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"policyNo\":\"\",\"question\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.instance").value("/api/v1/underwriting/evaluations"))
                .andExpect(jsonPath("$.violations.policyNo").isNotEmpty())
                .andExpect(jsonPath("$.violations.question").isNotEmpty())
                .andExpect(jsonPath("$.traceId").isNotEmpty())
                .andExpect(jsonPath("$.timestamp").isNotEmpty());
    }

    @Test
    void replaysAnIdempotentEvaluationAndRejectsAChangedPayload() throws Exception {
        String key = "api-idempotency-" + UUID.randomUUID();
        String body = """
                {"policyNo":"P-2001","question":"这张低风险办公楼保单是否可以承保？"}
                """;

        String created = mvc.perform(post("/api/v1/underwriting/evaluations")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotency-Replayed", "false"))
                .andReturn().getResponse().getContentAsString();
        String evaluationId = JsonPath.read(created, "$.id");

        mvc.perform(post("/api/v1/underwriting/evaluations")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotency-Replayed", "true"))
                .andExpect(jsonPath("$.id").value(evaluationId));

        mvc.perform(post("/api/v1/underwriting/evaluations")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"policyNo":"P-2001","question":"改成另一个业务问题"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("IDEMPOTENCY_KEY_CONFLICT"))
                .andExpect(jsonPath("$.instance").value("/api/v1/underwriting/evaluations"));
    }

    @Test
    void rejectsAnInvalidIdempotencyKeyWithProblemDetails() throws Exception {
        mvc.perform(post("/api/v1/underwriting/evaluations")
                        .header("Idempotency-Key", "contains spaces")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"policyNo":"P-2001","question":"是否承保？"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_IDEMPOTENCY_KEY"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    void replaysASavedDecisionAndReturnsAnIdempotentStructuredDriftAnalysis() throws Exception {
        String baselineJson = mvc.perform(post("/api/v1/underwriting/evaluations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"policyNo":"P-3001","question":"重新检查这张暴雨风险保单。"}
                                """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String baselineId = JsonPath.read(baselineJson, "$.id");
        String key = "decision-replay-" + UUID.randomUUID();

        String replayJson = mvc.perform(post(
                            "/api/v1/underwriting/evaluations/{id}/replays",
                            baselineId)
                        .header("Idempotency-Key", key))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotency-Replayed", "false"))
                .andExpect(header().string(HttpHeaders.LOCATION, containsString("/api/v1/underwriting/evaluations/")))
                .andExpect(jsonPath("$.baseline.id").value(baselineId))
                .andExpect(jsonPath("$.replay.replayedFromEvaluationId").value(baselineId))
                .andExpect(jsonPath("$.replay.ruleSet.version").value("property-underwriting-default-v1"))
                .andExpect(jsonPath("$.drift.baselineEvaluationId").value(baselineId))
                .andExpect(jsonPath("$.drift.decisionChange").value("UNCHANGED"))
                .andExpect(jsonPath("$.drift.riskScoreDelta").value(0))
                .andExpect(jsonPath("$.drift.severity").value("STABLE"))
                .andExpect(jsonPath("$.drift.promptChanged").value(false))
                .andExpect(jsonPath("$.drift.ruleSetChanged").value(false))
                .andExpect(jsonPath("$.drift.modelChanged").value(false))
                .andExpect(jsonPath("$.idempotencyReplayed").value(false))
                .andReturn().getResponse().getContentAsString();
        String replayId = JsonPath.read(replayJson, "$.replay.id");

        mvc.perform(post("/api/v1/underwriting/evaluations/{id}/replays", baselineId)
                        .header("Idempotency-Key", key))
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotency-Replayed", "true"))
                .andExpect(jsonPath("$.replay.id").value(replayId))
                .andExpect(jsonPath("$.idempotencyReplayed").value(true));

        mvc.perform(get("/api/v1/underwriting/evaluations/{id}/drift", replayId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.baselineEvaluationId").value(baselineId))
                .andExpect(jsonPath("$.replayEvaluationId").value(replayId))
                .andExpect(jsonPath("$.severity").value("STABLE"));

        mvc.perform(get("/api/v1/underwriting/evaluations/{id}/drift", baselineId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("EVALUATION_NOT_REPLAY"));

        mvc.perform(get("/actuator/metrics/underwriting.decision.replays"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availableTags[?(@.tag == 'severity')]").exists())
                .andExpect(jsonPath("$.availableTags[?(@.tag == 'outcome')]").exists());
    }

    @Test
    void exposesLowCardinalityEvaluationMetrics() throws Exception {
        mvc.perform(post("/api/v1/underwriting/evaluations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"policyNo":"P-2001","question":"记录一次指标。"}
                                """))
                .andExpect(status().isCreated());

        mvc.perform(get("/actuator/metrics/underwriting.evaluation.submissions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("underwriting.evaluation.submissions"))
                .andExpect(jsonPath("$.availableTags[?(@.tag == 'outcome')]").exists());

        mvc.perform(get("/actuator/metrics/underwriting.evaluation.decisions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availableTags[?(@.tag == 'decision')]").exists())
                .andExpect(jsonPath("$.availableTags[?(@.tag == 'risk_level')]").exists());
    }

    @Test
    void propagatesTheCallerTraceIdOnANotFoundProblem() throws Exception {
        mvc.perform(get("/api/v1/sessions/SES-MISSING").header("X-Trace-Id", "trace-interview-001"))
                .andExpect(status().isNotFound())
                .andExpect(header().string("X-Trace-Id", "trace-interview-001"))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.errorCode").value("SESSION_NOT_FOUND"))
                .andExpect(jsonPath("$.instance").value("/api/v1/sessions/SES-MISSING"))
                .andExpect(jsonPath("$.traceId").value("trace-interview-001"))
                .andExpect(jsonPath("$.timestamp").isNotEmpty());
    }
}
