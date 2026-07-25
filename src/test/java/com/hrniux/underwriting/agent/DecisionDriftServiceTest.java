package com.hrniux.underwriting.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.hrniux.underwriting.model.ModelResponse;
import com.hrniux.underwriting.prompt.PromptSnapshot;
import com.hrniux.underwriting.rag.DocumentType;
import com.hrniux.underwriting.rag.RetrievalMode;
import com.hrniux.underwriting.rule.Decision;
import com.hrniux.underwriting.rule.RiskLevel;
import com.hrniux.underwriting.rule.RuleResult;
import com.hrniux.underwriting.rule.RuleSetSnapshot;
import com.hrniux.underwriting.rule.RuleSeverity;

class DecisionDriftServiceTest {

    private final DecisionDriftService service = new DecisionDriftService();

    @Test
    void explainsDecisionRuleEvidencePromptAndRuleSetDrift() {
        UnderwritingEvaluation baseline = evaluation(
                "EVAL-BASE",
                Decision.APPROVE,
                RiskLevel.LOW,
                10,
                List.of(),
                List.of(evidence("DOC-1", "CHUNK-1", 1, 0.70, List.of("办公楼"))),
                new PromptSnapshot("underwriting-analysis", 1, "a".repeat(64)),
                new RuleSetSnapshot("rules-v1", "b".repeat(64), 5),
                null);
        UnderwritingEvaluation replay = evaluation(
                "EVAL-REPLAY",
                Decision.MANUAL_REVIEW,
                RiskLevel.MEDIUM,
                40,
                List.of(new RuleResult(
                        "RED_RAINSTORM",
                        "暴雨风险触发人工复核",
                        RuleSeverity.HIGH,
                        20,
                        Decision.MANUAL_REVIEW,
                        10)),
                List.of(
                        evidence("DOC-1", "CHUNK-1", 1, 0.82, List.of("办公楼", "暴雨")),
                        evidence("DOC-2", "CHUNK-2", 2, 0.76, List.of("暴雨"))),
                new PromptSnapshot("underwriting-analysis", 2, "c".repeat(64)),
                new RuleSetSnapshot("rules-v2", "d".repeat(64), 5),
                baseline.id());

        DecisionDrift drift = service.compare(baseline, replay);

        assertThat(drift.severity()).isEqualTo(DriftSeverity.DECISION_DRIFT);
        assertThat(drift.decisionChange()).isEqualTo(DecisionChange.STRENGTHENED);
        assertThat(drift.riskScoreDelta()).isEqualTo(30);
        assertThat(drift.addedRuleCodes()).containsExactly("RED_RAINSTORM");
        assertThat(drift.removedRuleCodes()).isEmpty();
        assertThat(drift.addedEvidence()).singleElement()
                .extracting(EvidenceReference::displayName)
                .isEqualTo("DOC-2@v2#CHUNK-2");
        assertThat(drift.evidenceScoreChanges()).singleElement().satisfies(change -> {
            assertThat(change.evidence().displayName()).isEqualTo("DOC-1@v1#CHUNK-1");
            assertThat(change.scoreDelta()).isCloseTo(0.12, org.assertj.core.data.Offset.offset(0.000_001));
            assertThat(change.retrievalExplanationChanged()).isTrue();
        });
        assertThat(drift.promptChanged()).isTrue();
        assertThat(drift.ruleSetChanged()).isTrue();
        assertThat(drift.modelChanged()).isFalse();
        assertThat(drift.sourceChanged()).isTrue();
        assertThat(drift.explanations()).anySatisfy(text -> assertThat(text).contains("更严格"));
        assertThat(drift.explanations()).anySatisfy(text -> assertThat(text).contains("规则集"));
    }

    @Test
    void classifiesSourceOnlyChangesWithoutClaimingDecisionDrift() {
        UnderwritingEvaluation baseline = evaluation(
                "EVAL-BASE",
                Decision.APPROVE,
                RiskLevel.LOW,
                10,
                List.of(),
                List.of(),
                new PromptSnapshot("underwriting-analysis", 1, "a".repeat(64)),
                new RuleSetSnapshot("rules-v1", "b".repeat(64), 5),
                null);
        UnderwritingEvaluation replay = evaluation(
                "EVAL-REPLAY",
                Decision.APPROVE,
                RiskLevel.LOW,
                10,
                List.of(),
                List.of(),
                new PromptSnapshot("underwriting-analysis", 2, "c".repeat(64)),
                baseline.ruleSet(),
                baseline.id());

        DecisionDrift drift = service.compare(baseline, replay);

        assertThat(drift.severity()).isEqualTo(DriftSeverity.SOURCE_DRIFT);
        assertThat(drift.decisionChange()).isEqualTo(DecisionChange.UNCHANGED);
        assertThat(drift.riskScoreDelta()).isZero();
        assertThat(drift.promptChanged()).isTrue();
        assertThat(drift.ruleSetChanged()).isFalse();
    }

    @Test
    void marksAReplayStableWhenDecisionAndEverySourceStayTheSame() {
        UnderwritingEvaluation baseline = evaluation(
                "EVAL-BASE",
                Decision.REJECT,
                RiskLevel.HIGH,
                60,
                List.of(),
                List.of(evidence("DOC-1", "CHUNK-1", 1, 0.70, List.of("火灾"))),
                new PromptSnapshot("underwriting-analysis", 1, "a".repeat(64)),
                new RuleSetSnapshot("rules-v1", "b".repeat(64), 5),
                null);
        UnderwritingEvaluation replay = evaluation(
                "EVAL-REPLAY",
                baseline.decision(),
                baseline.riskLevel(),
                baseline.riskScore(),
                baseline.ruleHits(),
                baseline.evidence(),
                baseline.modelResponse().prompt(),
                baseline.ruleSet(),
                baseline.id());

        DecisionDrift drift = service.compare(baseline, replay);

        assertThat(drift.severity()).isEqualTo(DriftSeverity.STABLE);
        assertThat(drift.sourceChanged()).isFalse();
        assertThat(drift.explanations()).contains(
                "核保结论未改变。",
                "当前业务事实、知识证据、Prompt、规则集和模型路由均与基线一致。");
    }

    @Test
    void flagsAWeakerReplayDecisionForPriorityReview() {
        UnderwritingEvaluation baseline = evaluation(
                "EVAL-BASE",
                Decision.REJECT,
                RiskLevel.HIGH,
                60,
                List.of(),
                List.of(),
                new PromptSnapshot("underwriting-analysis", 1, "a".repeat(64)),
                new RuleSetSnapshot("rules-v1", "b".repeat(64), 5),
                null);
        UnderwritingEvaluation replay = evaluation(
                "EVAL-REPLAY",
                Decision.APPROVE,
                RiskLevel.LOW,
                10,
                List.of(),
                List.of(),
                baseline.modelResponse().prompt(),
                baseline.ruleSet(),
                baseline.id());

        DecisionDrift drift = service.compare(baseline, replay);

        assertThat(drift.decisionChange()).isEqualTo(DecisionChange.WEAKENED);
        assertThat(drift.severity()).isEqualTo(DriftSeverity.DECISION_DRIFT);
        assertThat(drift.riskScoreDelta()).isEqualTo(-50);
        assertThat(drift.explanations()).anySatisfy(text -> assertThat(text).contains("更宽松"));
    }

    private UnderwritingEvaluation evaluation(
            String id,
            Decision decision,
            RiskLevel riskLevel,
            int riskScore,
            List<RuleResult> ruleHits,
            List<Evidence> evidence,
            PromptSnapshot prompt,
            RuleSetSnapshot ruleSet,
            String replayedFromEvaluationId) {
        return new UnderwritingEvaluation(
                id,
                "SES-" + id,
                "P-2001",
                "这张保单是否可以承保？",
                decision,
                riskLevel,
                riskScore,
                "核保摘要",
                List.of(),
                List.of(),
                List.of(),
                evidence,
                ruleHits,
                List.of(),
                List.of(),
                new ModelResponse(
                        "核保摘要",
                        List.of(),
                        List.of(),
                        "mock",
                        "deterministic-underwriting-mock",
                        1,
                        false,
                        prompt),
                Instant.parse("2026-07-25T12:00:00Z"),
                ruleSet,
                replayedFromEvaluationId);
    }

    private Evidence evidence(
            String documentId,
            String chunkId,
            int version,
            double score,
            List<String> matchedTerms) {
        return new Evidence(
                documentId,
                chunkId,
                "知识文档",
                DocumentType.RISK_GUIDE,
                "证据摘要",
                score,
                version,
                score,
                score,
                RetrievalMode.HYBRID,
                matchedTerms);
    }
}
