package com.hrniux.underwriting.agent;

import java.util.List;
import java.util.Objects;

import com.hrniux.underwriting.rule.Decision;
import com.hrniux.underwriting.rule.RiskLevel;

public record DecisionDrift(
        String baselineEvaluationId,
        String replayEvaluationId,
        String policyNo,
        Decision baselineDecision,
        Decision replayDecision,
        DecisionChange decisionChange,
        RiskLevel baselineRiskLevel,
        RiskLevel replayRiskLevel,
        int baselineRiskScore,
        int replayRiskScore,
        int riskScoreDelta,
        List<String> addedRuleCodes,
        List<String> removedRuleCodes,
        List<EvidenceReference> addedEvidence,
        List<EvidenceReference> removedEvidence,
        List<EvidenceScoreChange> evidenceScoreChanges,
        boolean promptChanged,
        boolean ruleSetChanged,
        boolean modelChanged,
        DriftSeverity severity,
        List<String> explanations) {

    public DecisionDrift {
        Objects.requireNonNull(baselineEvaluationId, "baselineEvaluationId must not be null");
        Objects.requireNonNull(replayEvaluationId, "replayEvaluationId must not be null");
        Objects.requireNonNull(policyNo, "policyNo must not be null");
        Objects.requireNonNull(baselineDecision, "baselineDecision must not be null");
        Objects.requireNonNull(replayDecision, "replayDecision must not be null");
        Objects.requireNonNull(decisionChange, "decisionChange must not be null");
        Objects.requireNonNull(baselineRiskLevel, "baselineRiskLevel must not be null");
        Objects.requireNonNull(replayRiskLevel, "replayRiskLevel must not be null");
        addedRuleCodes = List.copyOf(addedRuleCodes);
        removedRuleCodes = List.copyOf(removedRuleCodes);
        addedEvidence = List.copyOf(addedEvidence);
        removedEvidence = List.copyOf(removedEvidence);
        evidenceScoreChanges = List.copyOf(evidenceScoreChanges);
        Objects.requireNonNull(severity, "severity must not be null");
        explanations = List.copyOf(explanations);
    }

    public boolean sourceChanged() {
        return promptChanged
                || ruleSetChanged
                || modelChanged
                || !addedEvidence.isEmpty()
                || !removedEvidence.isEmpty()
                || !evidenceScoreChanges.isEmpty();
    }
}
