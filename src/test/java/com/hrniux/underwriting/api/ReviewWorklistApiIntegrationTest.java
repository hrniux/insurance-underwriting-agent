package com.hrniux.underwriting.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.hrniux.underwriting.agent.EvaluationRepository;
import com.hrniux.underwriting.agent.EvaluationRequest;
import com.hrniux.underwriting.agent.UnderwritingAgentOrchestrator;
import com.hrniux.underwriting.agent.UnderwritingEvaluation;
import com.hrniux.underwriting.review.HumanReviewCommand;
import com.hrniux.underwriting.review.HumanReviewOutcome;
import com.hrniux.underwriting.review.HumanReviewService;
import com.hrniux.underwriting.rule.Decision;

@SpringBootTest
class ReviewWorklistApiIntegrationTest {

    private static final String API = "/api/v1/underwriting/review-worklist";

    @Autowired private WebApplicationContext context;
    @Autowired private UnderwritingAgentOrchestrator orchestrator;
    @Autowired private EvaluationRepository evaluations;
    @Autowired private HumanReviewService reviews;

    private MockMvc mvc;
    private UnderwritingEvaluation template;
    private String policyNo;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
        template = orchestrator.evaluate(new EvaluationRequest(null, "P-2001", "生成真实工作列表测试基线。"));
        policyNo = "WL-" + UUID.randomUUID();
    }

    @Test
    void derivesReviewStatesAndFiltersBeforePagingWithoutChangingSavedData() throws Exception {
        String first = save("a", 70, "2026-01-01T00:00:00Z", Decision.MANUAL_REVIEW);
        String second = save("b", 70, "2026-01-01T00:00:00Z", Decision.MANUAL_REVIEW);
        String information = save("c", 80, "2026-01-02T00:00:00Z", Decision.MANUAL_REVIEW);
        String reviewed = save("d", 90, "2026-01-03T00:00:00Z", Decision.REJECT);
        reviews.submit(information, new HumanReviewCommand("UW-INFO", HumanReviewOutcome.MORE_INFORMATION_REQUIRED,
                "需要补充查勘证明。", List.of("查勘证明")));
        reviews.submit(reviewed, new HumanReviewCommand("UW-DONE", HumanReviewOutcome.REJECTED,
                "核验后拒绝承保。", List.of()));
        int evaluationCount = evaluations.findAll().size();
        int reviewCount = reviews.list().size();

        mvc.perform(get(API).param("policyNo", "  " + policyNo.toLowerCase() + "  ")
                        .param("status", "UNREVIEWED").param("decision", "MANUAL_REVIEW")
                        .param("page", "1").param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(1))
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(second))
                .andExpect(jsonPath("$.items[0].status").value("UNREVIEWED"))
                .andExpect(jsonPath("$.items[0].evidence").doesNotExist())
                .andExpect(jsonPath("$.items[0].toolTraces").doesNotExist());

        mvc.perform(get(API).param("policyNo", policyNo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(reviewed))
                .andExpect(jsonPath("$.items[0].status").value("REVIEWED"))
                .andExpect(jsonPath("$.items[0].reviewOutcome").value("REJECTED"))
                .andExpect(jsonPath("$.items[0].reviewerId").value("UW-DONE"))
                .andExpect(jsonPath("$.items[1].id").value(information))
                .andExpect(jsonPath("$.items[1].status").value("INFORMATION_REQUESTED"))
                .andExpect(jsonPath("$.items[2].id").value(first))
                .andExpect(jsonPath("$.items[3].id").value(second));

        mvc.perform(get(API).param("policyNo", policyNo).param("status", "INFORMATION_REQUESTED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].id").value(information));
        assertThat(evaluations.findAll()).hasSize(evaluationCount);
        assertThat(reviews.list()).hasSize(reviewCount);
        assertThat(evaluations.findById(reviewed)).isPresent().get()
                .extracting(UnderwritingEvaluation::decision).isEqualTo(Decision.REJECT);
    }

    @Test
    void usesOldestCreatedAtBeforeIdForEqualRisk() throws Exception {
        String newer = save("a", 70, "2026-01-02T00:00:00Z", Decision.MANUAL_REVIEW);
        String older = save("z", 70, "2026-01-01T00:00:00Z", Decision.MANUAL_REVIEW);
        mvc.perform(get(API).param("policyNo", policyNo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(older))
                .andExpect(jsonPath("$.items[1].id").value(newer));
    }

    @Test
    void handlesEmptyAndExtremePagesWithoutIntegerOverflow() throws Exception {
        save("only", 70, "2026-01-01T00:00:00Z", Decision.MANUAL_REVIEW);
        mvc.perform(get(API).param("policyNo", policyNo).param("page", "2147483647").param("size", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items").isEmpty());
        mvc.perform(get(API).param("policyNo", "DOES-NOT-EXIST"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(10))
                .andExpect(jsonPath("$.totalElements").value(0))
                .andExpect(jsonPath("$.totalPages").value(0))
                .andExpect(jsonPath("$.items").isEmpty());
    }

    @ParameterizedTest
    @CsvSource({"page,-1", "size,0", "size,51", "status,UNKNOWN", "decision,UNKNOWN", "page,not-a-number"})
    void rejectsInvalidFiltersAndPageBounds(String parameter, String value) throws Exception {
        mvc.perform(get(API).param(parameter, value))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
    }

    @Test
    void rejectsOversizedPolicyFilter() throws Exception {
        mvc.perform(get(API).param("policyNo", "P".repeat(65)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
    }

    private String save(String suffix, int score, String createdAt, Decision decision) {
        String id = policyNo + "-" + suffix;
        evaluations.save(new UnderwritingEvaluation(id, template.sessionId(), policyNo, template.question(),
                decision, template.riskLevel(), score, template.summary(), template.reasons(),
                template.recommendedActions(), template.degradations(), template.evidence(), template.ruleHits(),
                template.toolTraces(), template.stepTraces(), template.modelResponse(), Instant.parse(createdAt),
                template.ruleSet(), null));
        return id;
    }
}
