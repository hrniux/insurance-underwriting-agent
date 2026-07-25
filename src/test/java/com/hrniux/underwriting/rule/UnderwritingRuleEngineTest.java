package com.hrniux.underwriting.rule;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.hrniux.underwriting.demo.DemoScenarioRepository;
import com.hrniux.underwriting.tool.DisasterRiskFacts;
import com.hrniux.underwriting.tool.FakeUnderwritingFactTools;
import com.hrniux.underwriting.tool.HazardLevel;
import com.hrniux.underwriting.tool.SurveyReportFacts;

class UnderwritingRuleEngineTest {

    private FakeUnderwritingFactTools facts;
    private UnderwritingRuleEngine engine;

    @BeforeEach
    void setUp() {
        facts = new FakeUnderwritingFactTools(DemoScenarioRepository.loadDefault());
        engine = new UnderwritingRuleEngine();
    }

    @Test
    void appliesAHighRiskManualReviewFloorToTheWarehouse() {
        RuleEvaluation evaluation = engine.evaluate(context("P-1001"));

        assertThat(evaluation.decision()).isEqualTo(Decision.MANUAL_REVIEW);
        assertThat(evaluation.riskLevel()).isEqualTo(RiskLevel.HIGH);
        assertThat(evaluation.riskScore()).isGreaterThanOrEqualTo(70);
        assertThat(evaluation.hits()).extracting(RuleResult::code)
                .contains("RED_RAINSTORM", "REPEATED_CLAIMS", "HIGH_SUM_INSURED", "OPEN_REMEDIATION");
    }

    @Test
    void rejectsAnExtremeFireRiskWithACriticalProtectionDefect() {
        UnderwritingContext lowRisk = context("P-2001");
        SurveyReportFacts criticalSurvey = new SurveyReportFacts("P-2001", "CRITICAL_DEFECT", true,
                List.of("消防主泵无法启动"), "存在重大消防缺陷");
        DisasterRiskFacts extremeFire = new DisasterRiskFacts("P-2001", "高新示例区", HazardLevel.LOW,
                HazardLevel.LOW, HazardLevel.EXTREME, LocalDate.of(2026, 7, 1));

        RuleEvaluation evaluation = engine.evaluate(new UnderwritingContext(lowRisk.policy(), lowRisk.quotation(),
                lowRisk.history(), criticalSurvey, extremeFire));

        assertThat(evaluation.decision()).isEqualTo(Decision.REJECT);
        assertThat(evaluation.hits()).extracting(RuleResult::code).contains("CRITICAL_FIRE_DEFECT");
    }

    @Test
    void approvesTheLowRiskOfficeScenario() {
        RuleEvaluation evaluation = engine.evaluate(context("P-2001"));

        assertThat(evaluation.decision()).isEqualTo(Decision.APPROVE);
        assertThat(evaluation.riskLevel()).isEqualTo(RiskLevel.LOW);
        assertThat(evaluation.riskScore()).isLessThan(40);
        assertThat(evaluation.hits()).isEmpty();
    }

    @Test
    void appliesTwoRulesToTheMediumRiskTeachingScenario() {
        RuleEvaluation evaluation = engine.evaluate(context("P-3001"));

        assertThat(evaluation.decision()).isEqualTo(Decision.MANUAL_REVIEW);
        assertThat(evaluation.riskLevel()).isEqualTo(RiskLevel.MEDIUM);
        assertThat(evaluation.riskScore()).isEqualTo(40);
        assertThat(evaluation.hits()).extracting(RuleResult::code)
                .containsExactly("RED_RAINSTORM", "HIGH_SUM_INSURED");
    }

    @Test
    void rejectsTheExtremeFireTeachingScenario() {
        RuleEvaluation evaluation = engine.evaluate(context("P-4001"));

        assertThat(evaluation.decision()).isEqualTo(Decision.REJECT);
        assertThat(evaluation.riskLevel()).isEqualTo(RiskLevel.HIGH);
        assertThat(evaluation.riskScore()).isEqualTo(60);
        assertThat(evaluation.hits()).extracting(RuleResult::code)
                .containsExactly("CRITICAL_FIRE_DEFECT");
    }

    @Test
    void exposesAStableVersionedFingerprintForTheCompleteRuleSet() {
        RuleSetSnapshot snapshot = engine.snapshot();

        assertThat(snapshot.version()).isEqualTo("property-underwriting-default-v1");
        assertThat(snapshot.ruleCount()).isEqualTo(5);
        assertThat(snapshot.sha256()).matches("[0-9a-f]{64}");
        assertThat(snapshot.available()).isTrue();
        assertThat(new UnderwritingRuleEngine().snapshot()).isEqualTo(snapshot);
    }

    private UnderwritingContext context(String policyNo) {
        return new UnderwritingContext(
                facts.getPolicy(policyNo),
                facts.getQuotation(policyNo),
                facts.getUnderwritingHistory(policyNo),
                facts.getSurveyReport(policyNo),
                facts.getDisasterRisk(policyNo));
    }
}
