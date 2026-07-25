package com.hrniux.underwriting.rule;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import org.springframework.stereotype.Service;

@Service
public class UnderwritingRuleEngine {

    private static final int BASE_SCORE = 10;

    private final List<UnderwritingRule> rules;
    private final RuleSetSnapshot snapshot;

    public UnderwritingRuleEngine() {
        this(DefaultUnderwritingRules.rules(), DefaultUnderwritingRules.snapshot(BASE_SCORE));
    }

    UnderwritingRuleEngine(List<UnderwritingRule> rules) {
        this(rules, RuleSetSnapshot.legacy());
    }

    UnderwritingRuleEngine(List<UnderwritingRule> rules, RuleSetSnapshot snapshot) {
        this.rules = List.copyOf(rules);
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot must not be null");
    }

    public RuleEvaluation evaluate(UnderwritingContext context) {
        List<RuleResult> hits = rules.stream()
                .map(rule -> rule.evaluate(context))
                .flatMap(java.util.Optional::stream)
                .sorted(Comparator.comparingInt(RuleResult::priority).thenComparing(RuleResult::code))
                .toList();

        int score = Math.max(0, Math.min(100,
                BASE_SCORE + hits.stream().mapToInt(RuleResult::scoreImpact).sum()));
        Decision decision = hits.stream()
                .map(RuleResult::decision)
                .reduce(Decision.APPROVE, Decision::strongest);

        return new RuleEvaluation(decision, RiskLevel.fromScore(score), score, hits);
    }

    public RuleSetSnapshot snapshot() {
        return snapshot;
    }
}
