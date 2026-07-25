package com.hrniux.underwriting.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest
class DemoConsoleStaticResourceTest {

    @Autowired
    private WebApplicationContext context;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @Test
    void exposesTheDemoConsoleAtAStableTrailingSlashUrl() throws Exception {
        mvc.perform(get("/demo"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/demo/"));

        mvc.perform(get("/demo/"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/demo/index.html"));
    }

    @Test
    void servesAnAccessibleChineseDemoShell() throws Exception {
        String html = mvc.perform(get("/demo/index.html"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, containsString("text/html")))
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(html).contains(
                "中文智能核保演示台",
                "本页面全部业务数据均为虚构数据",
                "id=\"scenario-list\"",
                "id=\"scenario-filters\"",
                "id=\"run-evaluation\"",
                "id=\"question-input\"",
                "id=\"service-status\"",
                "id=\"theme-toggle\"",
                "id=\"run-comparison\"",
                "id=\"comparison-status\"",
                "id=\"comparison-panel\"",
                "id=\"comparison-summary\"",
                "id=\"comparison-grid\"",
                "id=\"source-snapshot\"",
                "id=\"degradation-list\"",
                "id=\"live-pipeline\"",
                "id=\"live-pipeline-steps\"",
                "id=\"result-metrics\"",
                "id=\"result-tabs\"",
                "role=\"tablist\"",
                "id=\"copy-summary\"",
                "id=\"export-json\"",
                "id=\"history-list\"",
                "id=\"human-review-panel\"",
                "id=\"review-form\"",
                "id=\"reviewer-id\"",
                "id=\"review-outcome\"",
                "id=\"review-comment\"",
                "id=\"submit-review\"",
                "id=\"report-action\"",
                "id=\"download-report\"",
                "id=\"decision-replay-panel\"",
                "id=\"run-decision-replay\"",
                "id=\"decision-replay-status\"",
                "id=\"decision-drift-result\"",
                "下载中文 Markdown 报告",
                "决策重演与漂移分析",
                "场景组合横向对比",
                "aria-live=\"polite\"");
    }

    @Test
    void servesAClientThatUsesExistingApisAndSafeDomRendering() throws Exception {
        mvc.perform(get("/demo/app.js"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, containsString("javascript")));

        assertThat(readClientAsset("static/demo/app.js"))
                .contains(
                        "/api/v1/demo/scenarios",
                        "/api/v1/underwriting/evaluations",
                        "AbortController",
                        "comparisonController",
                        "async function runComparison()",
                        "for (const scenario of state.scenarios)",
                        "renderComparisonSummary",
                        "renderComparisonCard",
                        "async function checkServiceHealth()",
                        "function startLivePipeline()",
                        "function completeLivePipeline(evaluation, durationMs)",
                        "function renderResultMetrics(evaluation, durationMs)",
                        "function activateResultTab(tabName, focus = false)",
                        "function recordHistory(evaluation, durationMs)",
                        "async function restoreHistory(evaluationId)",
                        "async function copyEvaluationSummary()",
                        "function exportEvaluationJson()",
                        "sessionStorage",
                        "navigator.clipboard.writeText",
                        "renderDegradations",
                        "renderSourceSnapshot",
                        "决策来源快照",
                        "规则集快照",
                        "知识版本引用",
                        "async function runDecisionReplay()",
                        "/replays",
                        "renderDecisionDrift",
                        "driftSeverities",
                        "Idempotency-Key",
                        "HYBRID: \"混合检索\"",
                        "renderHumanReviewPanel",
                        "async function submitHumanReview(event)",
                        "人工复核结论已保存且不可覆盖",
                        "MORE_INFORMATION_REQUIRED: \"要求补充资料\"",
                        "DATA_SOURCE_DEGRADED",
                        "安全降级已覆盖常规场景预期",
                        "安全覆盖",
                        "DEGRADED: \"降级完成\"",
                        "UNKNOWN: \"未知\"",
                        "configureReportDownload",
                        "encodeURIComponent(evaluationId)",
                        "removeAttribute(\"href\")",
                        "removeAttribute(\"download\")",
                        "部分场景运行失败",
                        "查看单场景详情",
                        "replaceChildren",
                        "textContent",
                        "scrollIntoView",
                        "aria-pressed",
                        "progressbar",
                        "PRODUCT_CLAUSE: \"保险条款\"",
                        "UNDERWRITING_RULE: \"核保规则\"",
                        "RISK_GUIDE: \"风险指引\"",
                        "HISTORICAL_CASE: \"历史案例\"",
                        "EXTREME: \"极端\"")
                .doesNotContain("innerHTML");
    }

    @Test
    void servesResponsiveAccessibleStylesWithoutExternalAssets() throws Exception {
        mvc.perform(get("/demo/styles.css"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, containsString("text/css")));

        assertThat(readClientAsset("static/demo/styles.css"))
                .contains(
                        "@media (max-width: 760px)",
                        ":focus-visible",
                        "prefers-reduced-motion",
                        ".comparison-action",
                        ".comparison-panel",
                        ".comparison-summary",
                        ".comparison-card",
                        ".comparison-card--failed",
                        ".comparison--safety",
                        ".comparison-risk",
                        ".comparison-card__detail",
                        ".report-action",
                        ".report-download",
                        ".decision-replay-panel",
                        ".drift-summary",
                        ".drift-metrics",
                        ".drift-source-flag",
                        ".degradation-notice",
                        ".human-review-panel",
                        ".review-form",
                        ".human-review-record",
                        ".app-bar",
                        ".service-status[data-status=\"UP\"]",
                        ".question-editor",
                        ".live-pipeline__steps",
                        ".result-metrics",
                        ".result-tabs",
                        ".history-list",
                        ":root[data-theme=\"dark\"]",
                        ".step-item[data-status=\"DEGRADED\"]")
                .doesNotContain("@import", "url(http://", "url(https://");
    }

    private String readClientAsset(String path) throws IOException {
        try (var stream = new ClassPathResource(path).getInputStream()) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
