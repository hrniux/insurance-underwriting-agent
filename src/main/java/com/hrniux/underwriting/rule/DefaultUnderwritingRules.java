package com.hrniux.underwriting.rule;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import com.hrniux.underwriting.tool.HazardLevel;

public final class DefaultUnderwritingRules {

    public static final String RULE_SET_VERSION = "property-underwriting-default-v1";

    private static final BigDecimal HIGH_SUM_INSURED = new BigDecimal("10000000");

    private DefaultUnderwritingRules() {
    }

    public static List<UnderwritingRule> rules() {
        return List.of(
                DefaultUnderwritingRules::criticalFireDefect,
                DefaultUnderwritingRules::redRainstorm,
                DefaultUnderwritingRules::repeatedClaims,
                DefaultUnderwritingRules::highSumInsured,
                DefaultUnderwritingRules::openRemediation);
    }

    public static RuleSetSnapshot snapshot(int baseScore) {
        return RuleSetSnapshot.capture(RULE_SET_VERSION, baseScore, List.of(
                "CRITICAL_FIRE_DEFECT|fireProtection=CRITICAL_DEFECT_OR_MISSING&fire=EXTREME"
                        + "|severity=CRITICAL|score=50|decision=REJECT|priority=5",
                "RED_RAINSTORM|rainstorm>=RED"
                        + "|severity=HIGH|score=20|decision=MANUAL_REVIEW|priority=10",
                "REPEATED_CLAIMS|claimCountThreeYears>=2"
                        + "|severity=HIGH|score=15|decision=MANUAL_REVIEW|priority=20",
                "HIGH_SUM_INSURED|sumInsured>=10000000"
                        + "|severity=WARNING|score=10|decision=MANUAL_REVIEW|priority=30",
                "OPEN_REMEDIATION|drainageRemediationCompleted=false"
                        + "|severity=HIGH|score=15|decision=MANUAL_REVIEW|priority=40"));
    }

    private static Optional<RuleResult> criticalFireDefect(UnderwritingContext context) {
        boolean criticalProtection = "CRITICAL_DEFECT".equals(context.survey().fireProtectionStatus())
                || "MISSING".equals(context.survey().fireProtectionStatus());
        if (criticalProtection && context.disaster().fire() == HazardLevel.EXTREME) {
            return hit("CRITICAL_FIRE_DEFECT", "极端火灾暴露且消防设施存在重大缺陷", RuleSeverity.CRITICAL,
                    50, Decision.REJECT, 5);
        }
        return Optional.empty();
    }

    private static Optional<RuleResult> redRainstorm(UnderwritingContext context) {
        if (context.disaster().rainstorm().ordinal() >= HazardLevel.RED.ordinal()) {
            return hit("RED_RAINSTORM", "标的所在区域暴雨风险达到红色等级", RuleSeverity.HIGH,
                    20, Decision.MANUAL_REVIEW, 10);
        }
        return Optional.empty();
    }

    private static Optional<RuleResult> repeatedClaims(UnderwritingContext context) {
        if (context.history().claimCountThreeYears() >= 2) {
            return hit("REPEATED_CLAIMS", "近三年出险次数达到两次及以上", RuleSeverity.HIGH,
                    15, Decision.MANUAL_REVIEW, 20);
        }
        return Optional.empty();
    }

    private static Optional<RuleResult> highSumInsured(UnderwritingContext context) {
        if (context.quotation().sumInsured().compareTo(HIGH_SUM_INSURED) >= 0) {
            return hit("HIGH_SUM_INSURED", "保险金额达到一千万元及以上", RuleSeverity.WARNING,
                    10, Decision.MANUAL_REVIEW, 30);
        }
        return Optional.empty();
    }

    private static Optional<RuleResult> openRemediation(UnderwritingContext context) {
        if (!context.survey().drainageRemediationCompleted()) {
            return hit("OPEN_REMEDIATION", "风险查勘中的排水整改尚未完成", RuleSeverity.HIGH,
                    15, Decision.MANUAL_REVIEW, 40);
        }
        return Optional.empty();
    }

    private static Optional<RuleResult> hit(
            String code,
            String reason,
            RuleSeverity severity,
            int scoreImpact,
            Decision decision,
            int priority) {
        return Optional.of(new RuleResult(code, reason, severity, scoreImpact, decision, priority));
    }
}
