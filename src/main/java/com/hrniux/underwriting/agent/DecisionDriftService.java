package com.hrniux.underwriting.agent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

import org.springframework.stereotype.Service;

import com.hrniux.underwriting.model.ModelResponse;
import com.hrniux.underwriting.rule.Decision;
import com.hrniux.underwriting.rule.RuleResult;

@Service
public class DecisionDriftService {

    private static final double SCORE_EPSILON = 0.000_001;

    public DecisionDrift compare(UnderwritingEvaluation baseline, UnderwritingEvaluation replay) {
        Objects.requireNonNull(baseline, "baseline must not be null");
        Objects.requireNonNull(replay, "replay must not be null");
        if (!baseline.policyNo().equals(replay.policyNo())) {
            throw new IllegalArgumentException("evaluations must reference the same policy");
        }

        DecisionChange decisionChange = decisionChange(baseline.decision(), replay.decision());
        int riskScoreDelta = replay.riskScore() - baseline.riskScore();
        List<String> addedRules = difference(ruleCodes(replay), ruleCodes(baseline));
        List<String> removedRules = difference(ruleCodes(baseline), ruleCodes(replay));

        Map<EvidenceReference, Evidence> baselineEvidence = evidenceByReference(baseline.evidence());
        Map<EvidenceReference, Evidence> replayEvidence = evidenceByReference(replay.evidence());
        List<EvidenceReference> addedEvidence = evidenceDifference(replayEvidence, baselineEvidence);
        List<EvidenceReference> removedEvidence = evidenceDifference(baselineEvidence, replayEvidence);
        List<EvidenceScoreChange> evidenceScoreChanges = scoreChanges(baselineEvidence, replayEvidence);

        boolean promptChanged = !baseline.modelResponse().prompt().equals(replay.modelResponse().prompt());
        boolean ruleSetChanged = !baseline.ruleSet().equals(replay.ruleSet());
        boolean modelChanged = modelChanged(baseline.modelResponse(), replay.modelResponse());
        boolean decisionDrift = decisionChange != DecisionChange.UNCHANGED
                || riskScoreDelta != 0
                || baseline.riskLevel() != replay.riskLevel()
                || !addedRules.isEmpty()
                || !removedRules.isEmpty();
        boolean sourceDrift = promptChanged
                || ruleSetChanged
                || modelChanged
                || !addedEvidence.isEmpty()
                || !removedEvidence.isEmpty()
                || !evidenceScoreChanges.isEmpty();
        DriftSeverity severity = decisionDrift
                ? DriftSeverity.DECISION_DRIFT
                : sourceDrift ? DriftSeverity.SOURCE_DRIFT : DriftSeverity.STABLE;

        return new DecisionDrift(
                baseline.id(),
                replay.id(),
                baseline.policyNo(),
                baseline.decision(),
                replay.decision(),
                decisionChange,
                baseline.riskLevel(),
                replay.riskLevel(),
                baseline.riskScore(),
                replay.riskScore(),
                riskScoreDelta,
                addedRules,
                removedRules,
                addedEvidence,
                removedEvidence,
                evidenceScoreChanges,
                promptChanged,
                ruleSetChanged,
                modelChanged,
                severity,
                explanations(
                        decisionChange,
                        riskScoreDelta,
                        baseline.riskLevel() != replay.riskLevel(),
                        addedRules,
                        removedRules,
                        addedEvidence,
                        removedEvidence,
                        evidenceScoreChanges,
                        promptChanged,
                        ruleSetChanged,
                        modelChanged));
    }

    private DecisionChange decisionChange(Decision baseline, Decision replay) {
        if (baseline == replay) {
            return DecisionChange.UNCHANGED;
        }
        return replay.ordinal() > baseline.ordinal()
                ? DecisionChange.STRENGTHENED
                : DecisionChange.WEAKENED;
    }

    private Set<String> ruleCodes(UnderwritingEvaluation evaluation) {
        return evaluation.ruleHits().stream()
                .map(RuleResult::code)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    private List<String> difference(Set<String> left, Set<String> right) {
        TreeSet<String> result = new TreeSet<>(left);
        result.removeAll(right);
        return List.copyOf(result);
    }

    private Map<EvidenceReference, Evidence> evidenceByReference(List<Evidence> evidence) {
        Map<EvidenceReference, Evidence> result = new LinkedHashMap<>();
        evidence.forEach(item -> result.putIfAbsent(EvidenceReference.from(item), item));
        return result;
    }

    private List<EvidenceReference> evidenceDifference(
            Map<EvidenceReference, Evidence> left,
            Map<EvidenceReference, Evidence> right) {
        return left.keySet().stream()
                .filter(reference -> !right.containsKey(reference))
                .sorted()
                .toList();
    }

    private List<EvidenceScoreChange> scoreChanges(
            Map<EvidenceReference, Evidence> baseline,
            Map<EvidenceReference, Evidence> replay) {
        List<EvidenceScoreChange> changes = new ArrayList<>();
        baseline.forEach((reference, before) -> {
            Evidence after = replay.get(reference);
            if (after == null) {
                return;
            }
            double delta = after.score() - before.score();
            boolean explanationChanged = before.retrievalMode() != after.retrievalMode()
                    || !before.matchedTerms().equals(after.matchedTerms())
                    || Math.abs(after.vectorScore() - before.vectorScore()) >= SCORE_EPSILON
                    || Math.abs(after.lexicalScore() - before.lexicalScore()) >= SCORE_EPSILON;
            if (Math.abs(delta) >= SCORE_EPSILON || explanationChanged) {
                changes.add(new EvidenceScoreChange(
                        reference,
                        before.score(),
                        after.score(),
                        delta,
                        explanationChanged));
            }
        });
        changes.sort(java.util.Comparator.comparing(EvidenceScoreChange::evidence));
        return List.copyOf(changes);
    }

    private boolean modelChanged(ModelResponse baseline, ModelResponse replay) {
        return !baseline.provider().equals(replay.provider())
                || !baseline.model().equals(replay.model())
                || baseline.fallbackUsed() != replay.fallbackUsed();
    }

    private List<String> explanations(
            DecisionChange decisionChange,
            int scoreDelta,
            boolean riskLevelChanged,
            List<String> addedRules,
            List<String> removedRules,
            List<EvidenceReference> addedEvidence,
            List<EvidenceReference> removedEvidence,
            List<EvidenceScoreChange> scoreChanges,
            boolean promptChanged,
            boolean ruleSetChanged,
            boolean modelChanged) {
        List<String> explanations = new ArrayList<>();
        explanations.add(switch (decisionChange) {
            case UNCHANGED -> "核保结论未改变。";
            case STRENGTHENED -> "重演结论比基线更严格。";
            case WEAKENED -> "重演结论比基线更宽松，需要确认变化依据。";
        });
        if (scoreDelta != 0) {
            explanations.add("风险分" + (scoreDelta > 0 ? "上升 " : "下降 ") + Math.abs(scoreDelta) + " 分。");
        }
        if (riskLevelChanged) {
            explanations.add("风险等级发生变化。");
        }
        if (!addedRules.isEmpty()) {
            explanations.add("新增命中规则：" + String.join("、", addedRules) + "。");
        }
        if (!removedRules.isEmpty()) {
            explanations.add("不再命中规则：" + String.join("、", removedRules) + "。");
        }
        if (!addedEvidence.isEmpty() || !removedEvidence.isEmpty()) {
            explanations.add("知识证据引用发生变化：新增 " + addedEvidence.size()
                    + " 条，移除 " + removedEvidence.size() + " 条。");
        }
        if (!scoreChanges.isEmpty()) {
            explanations.add("有 " + scoreChanges.size() + " 条共同证据的检索分数或解释发生变化。");
        }
        if (promptChanged) {
            explanations.add("实际使用的 Prompt 版本或模板指纹发生变化。");
        }
        if (ruleSetChanged) {
            explanations.add("确定性规则集版本或指纹发生变化。");
        }
        if (modelChanged) {
            explanations.add("模型提供方、模型名称或降级状态发生变化。");
        }
        if (explanations.size() == 1 && decisionChange == DecisionChange.UNCHANGED) {
            explanations.add("当前业务事实、知识证据、Prompt、规则集和模型路由均与基线一致。");
        }
        return List.copyOf(explanations);
    }
}
