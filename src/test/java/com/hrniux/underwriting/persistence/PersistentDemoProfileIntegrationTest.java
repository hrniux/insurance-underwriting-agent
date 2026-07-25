package com.hrniux.underwriting.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.hrniux.underwriting.agent.EvaluationRepository;
import com.hrniux.underwriting.agent.Evidence;
import com.hrniux.underwriting.agent.JdbcEvaluationRepository;
import com.hrniux.underwriting.model.ModelResponse;
import com.hrniux.underwriting.rag.RetrievalMode;
import com.hrniux.underwriting.review.HumanReviewRepository;
import com.hrniux.underwriting.review.JdbcHumanReviewRepository;
import com.hrniux.underwriting.session.JdbcSessionRepository;
import com.hrniux.underwriting.session.SessionRepository;
import com.hrniux.underwriting.shared.persistence.PersistenceJsonCodec;
import com.jayway.jsonpath.JsonPath;

@SpringBootTest
@ActiveProfiles("persistent-demo")
@TestPropertySource(properties =
        "spring.datasource.url=jdbc:h2:mem:persistent-demo-it;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE")
class PersistentDemoProfileIntegrationTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private SessionRepository sessions;

    @Autowired
    private EvaluationRepository evaluations;

    @Autowired
    private HumanReviewRepository reviews;

    @Autowired
    private PersistenceJsonCodec json;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
        jdbc.update("DELETE FROM underwriting_human_review");
        jdbc.update("DELETE FROM underwriting_evaluation");
        jdbc.update("DELETE FROM underwriting_session");
    }

    @Test
    void selectsJdbcAdaptersOnlyForThePersistentProfile() {
        assertThat(sessions).isInstanceOf(JdbcSessionRepository.class);
        assertThat(evaluations).isInstanceOf(JdbcEvaluationRepository.class);
        assertThat(reviews).isInstanceOf(JdbcHumanReviewRepository.class);
    }

    @Test
    void readsLegacyModelAndEvidenceJsonWithoutTheNewProvenanceFields() {
        ModelResponse model = json.read("""
                {
                  "summary":"历史模型摘要",
                  "reasons":[],
                  "recommendedActions":[],
                  "provider":"mock",
                  "model":"legacy-model",
                  "attempts":1,
                  "fallbackUsed":false
                }
                """, ModelResponse.class);
        Evidence evidence = json.read("""
                {
                  "documentId":"DOC-LEGACY",
                  "chunkId":"CHUNK-LEGACY",
                  "title":"历史知识证据",
                  "type":"RISK_GUIDE",
                  "excerpt":"历史记录没有来源快照字段",
                  "score":0.75
                }
                """, Evidence.class);

        assertThat(model.prompt().available()).isFalse();
        assertThat(evidence.knowledgeVersion()).isZero();
        assertThat(evidence.retrievalMode()).isEqualTo(RetrievalMode.UNKNOWN);
        assertThat(evidence.matchedTerms()).isEmpty();
    }

    @Test
    void roundTripsSessionEvaluationAndImmutableReviewThroughH2() throws Exception {
        String sessionJson = mvc.perform(post("/api/v1/sessions"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String sessionId = JsonPath.read(sessionJson, "$.id");

        String evaluationJson = mvc.perform(post("/api/v1/underwriting/evaluations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sessionId":"%s",
                                  "policyNo":"P-1001",
                                  "question":"验证文件数据库中的核保闭环。"
                                }
                                """.formatted(sessionId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.decision").value("MANUAL_REVIEW"))
                .andReturn().getResponse().getContentAsString();
        String evaluationId = JsonPath.read(evaluationJson, "$.id");

        String replayJson = mvc.perform(post(
                            "/api/v1/underwriting/evaluations/{id}/replays",
                            evaluationId)
                        .header("Idempotency-Key", "persistent-replay-" + evaluationId))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.replay.replayedFromEvaluationId").value(evaluationId))
                .andExpect(jsonPath("$.replay.ruleSet.version").value("property-underwriting-default-v1"))
                .andExpect(jsonPath("$.drift.severity").value("STABLE"))
                .andReturn().getResponse().getContentAsString();
        String replayId = JsonPath.read(replayJson, "$.replay.id");

        String reviewPath = "/api/v1/underwriting/evaluations/" + evaluationId + "/review";
        mvc.perform(post(reviewPath)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "reviewerId":"UW-PERSISTENCE-TEST",
                                  "outcome":"APPROVED",
                                  "comment":"重启恢复验证通过后附条件承保。",
                                  "conditions":["提高免赔额"]
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.relationship").value("RESOLVED_MANUAL_REVIEW"));

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM underwriting_session", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM underwriting_evaluation", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM underwriting_human_review", Integer.class)).isEqualTo(1);
        assertThat(sessions.findById(sessionId)).isPresent().get()
                .extracting(session -> session.messages().size()).isEqualTo(2);
        assertThat(evaluations.findById(evaluationId)).isPresent().get()
                .satisfies(evaluation -> {
                    assertThat(evaluation.policyNo()).isEqualTo("P-1001");
                    assertThat(evaluation.modelResponse().prompt().code()).isEqualTo("underwriting-analysis");
                    assertThat(evaluation.modelResponse().prompt().version()).isPositive();
                    assertThat(evaluation.ruleSet().version()).isEqualTo("property-underwriting-default-v1");
                    assertThat(evaluation.ruleSet().ruleCount()).isEqualTo(5);
                    assertThat(evaluation.evidence()).allSatisfy(evidence -> {
                        assertThat(evidence.knowledgeVersion()).isPositive();
                        assertThat(evidence.retrievalMode()).isNotNull();
                    });
                });
        assertThat(evaluations.findById(replayId)).isPresent().get()
                .satisfies(replay -> {
                    assertThat(replay.replayedFromEvaluationId()).isEqualTo(evaluationId);
                    assertThat(replay.sessionId()).isNotEqualTo(sessionId);
                    assertThat(replay.ruleSet().available()).isTrue();
                });
        assertThat(reviews.findByEvaluationId(evaluationId)).isPresent().get()
                .extracting(review -> review.reviewerId()).isEqualTo("UW-PERSISTENCE-TEST");

        mvc.perform(get("/api/v1/sessions/{id}", sessionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messages.length()").value(2));
        mvc.perform(get("/api/v1/underwriting/evaluations/{id}", evaluationId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.policyNo").value("P-1001"));
        mvc.perform(get("/api/v1/underwriting/evaluations/{id}/drift", replayId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.baselineEvaluationId").value(evaluationId))
                .andExpect(jsonPath("$.severity").value("STABLE"));
        mvc.perform(get(reviewPath))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewerId").value("UW-PERSISTENCE-TEST"));

        mvc.perform(post(reviewPath)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "reviewerId":"UW-OVERWRITE",
                                  "outcome":"REJECTED",
                                  "comment":"不允许覆盖首条复核结论。",
                                  "conditions":[]
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("HUMAN_REVIEW_ALREADY_EXISTS"));
    }
}
