package com.hrniux.underwriting.agent;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

import com.hrniux.underwriting.model.ModelResponse;
import com.hrniux.underwriting.rule.Decision;
import com.hrniux.underwriting.rule.RiskLevel;
import com.hrniux.underwriting.rule.RuleResult;
import com.hrniux.underwriting.rule.RuleSetSnapshot;
import com.hrniux.underwriting.tool.ToolCallTrace;

public record UnderwritingEvaluation(
        String id,
        String sessionId,
        String policyNo,
        String question,
        Decision decision,
        RiskLevel riskLevel,
        int riskScore,
        String summary,
        List<String> reasons,
        List<String> recommendedActions,
        List<DegradationNotice> degradations,
        List<Evidence> evidence,
        List<RuleResult> ruleHits,
        List<ToolCallTrace> toolTraces,
        List<StepTrace> stepTraces,
        ModelResponse modelResponse,
        Instant createdAt,
        RuleSetSnapshot ruleSet,
        String replayedFromEvaluationId) {

    public UnderwritingEvaluation {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        Objects.requireNonNull(policyNo, "policyNo must not be null");
        Objects.requireNonNull(question, "question must not be null");
        Objects.requireNonNull(decision, "decision must not be null");
        Objects.requireNonNull(riskLevel, "riskLevel must not be null");
        Objects.requireNonNull(summary, "summary must not be null");
        reasons = List.copyOf(reasons);
        recommendedActions = List.copyOf(recommendedActions);
        degradations = List.copyOf(degradations);
        evidence = List.copyOf(evidence);
        ruleHits = List.copyOf(ruleHits);
        toolTraces = List.copyOf(toolTraces);
        stepTraces = List.copyOf(stepTraces);
        Objects.requireNonNull(modelResponse, "modelResponse must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        ruleSet = ruleSet == null ? RuleSetSnapshot.legacy() : ruleSet;
        replayedFromEvaluationId = replayedFromEvaluationId == null || replayedFromEvaluationId.isBlank()
                ? null
                : replayedFromEvaluationId.trim();
    }

    public UnderwritingEvaluation(
            String id,
            String sessionId,
            String policyNo,
            String question,
            Decision decision,
            RiskLevel riskLevel,
            int riskScore,
            String summary,
            List<String> reasons,
            List<String> recommendedActions,
            List<DegradationNotice> degradations,
            List<Evidence> evidence,
            List<RuleResult> ruleHits,
            List<ToolCallTrace> toolTraces,
            List<StepTrace> stepTraces,
            ModelResponse modelResponse,
            Instant createdAt) {
        this(id, sessionId, policyNo, question, decision, riskLevel, riskScore, summary,
                reasons, recommendedActions, degradations, evidence, ruleHits, toolTraces, stepTraces,
                modelResponse, createdAt, RuleSetSnapshot.legacy(), null);
    }

    public UnderwritingEvaluation withStepTraces(List<StepTrace> traces) {
        return new UnderwritingEvaluation(id, sessionId, policyNo, question, decision, riskLevel, riskScore, summary,
                reasons, recommendedActions, degradations, evidence, ruleHits, toolTraces, traces, modelResponse,
                createdAt, ruleSet, replayedFromEvaluationId);
    }

    public boolean replay() {
        return replayedFromEvaluationId != null;
    }
}
