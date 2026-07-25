package com.hrniux.underwriting.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.hrniux.underwriting.demo.DemoScenarioRepository;
import com.hrniux.underwriting.model.DeterministicMockModelGateway;
import com.hrniux.underwriting.prompt.PromptTemplateService;
import com.hrniux.underwriting.prompt.PromptSnapshot;
import com.hrniux.underwriting.prompt.RenderedPrompt;
import com.hrniux.underwriting.rag.DocumentChunk;
import com.hrniux.underwriting.rag.DocumentType;
import com.hrniux.underwriting.rag.KnowledgeService;
import com.hrniux.underwriting.rag.RetrievalHit;
import com.hrniux.underwriting.rule.Decision;
import com.hrniux.underwriting.rule.UnderwritingRuleEngine;
import com.hrniux.underwriting.session.InMemorySessionRepository;
import com.hrniux.underwriting.session.SessionRole;
import com.hrniux.underwriting.session.SessionService;
import com.hrniux.underwriting.shared.error.ResourceNotFoundException;
import com.hrniux.underwriting.tool.FakeUnderwritingFactTools;
import com.hrniux.underwriting.tool.ToolCallStatus;
import com.hrniux.underwriting.tool.ToolName;
import com.hrniux.underwriting.tool.ToolRegistry;
import com.hrniux.underwriting.tool.UnderwritingFactTools;

@SpringBootTest
class UnderwritingAgentOrchestratorTest {

    @Autowired
    private UnderwritingAgentOrchestrator orchestrator;

    @Autowired
    private SessionService sessions;

    @Test
    void executesTheExplainableSevenStepPipelineAndPersistsTheResult() {
        UnderwritingEvaluation evaluation = orchestrator.evaluate(
                new EvaluationRequest(null, "P-1001", "暴雨风险较高，这张仓库财产险能否承保？"));

        assertThat(evaluation.sessionId()).isNotBlank();
        assertThat(evaluation.stepTraces()).extracting(StepTrace::step).containsExactly(AgentStep.values());
        assertThat(evaluation.stepTraces()).extracting(StepTrace::status)
                .containsOnly(StepStatus.SUCCESS);
        assertThat(evaluation.toolTraces()).hasSize(6);
        assertThat(evaluation.toolTraces()).extracting(trace -> trace.toolName())
                .contains(ToolName.GET_POLICY, ToolName.GET_QUOTATION, ToolName.GET_UNDERWRITING_HISTORY,
                        ToolName.GET_SURVEY_REPORT, ToolName.GET_DISASTER_RISK, ToolName.VALIDATE_RULES);
        assertThat(evaluation.evidence()).isNotEmpty();
        assertThat(evaluation.evidence()).allSatisfy(evidence -> {
            assertThat(evidence.knowledgeVersion()).isPositive();
            assertThat(evidence.retrievalMode()).isNotNull();
        });
        assertThat(evaluation.modelResponse().prompt().code()).isEqualTo("underwriting-analysis");
        assertThat(evaluation.modelResponse().prompt().version()).isPositive();
        assertThat(evaluation.modelResponse().prompt().templateSha256()).matches("[0-9a-f]{64}");
        assertThat(evaluation.ruleSet().version()).isEqualTo("property-underwriting-default-v1");
        assertThat(evaluation.ruleSet().ruleCount()).isEqualTo(5);
        assertThat(evaluation.ruleSet().sha256()).matches("[0-9a-f]{64}");
        assertThat(evaluation.replayedFromEvaluationId()).isNull();
        assertThat(evaluation.decision().ordinal()).isGreaterThanOrEqualTo(Decision.MANUAL_REVIEW.ordinal());
        assertThat(orchestrator.getEvaluation(evaluation.id())).isEqualTo(evaluation);
        assertThat(sessions.getSession(evaluation.sessionId()).messages())
                .extracting(message -> message.role())
                .containsExactly(SessionRole.USER, SessionRole.ASSISTANT);
    }

    @Test
    void raisesTheDecisionFloorWhenKnowledgeEvidenceIsMissing() {
        KnowledgeService emptyKnowledge = mock(KnowledgeService.class);
        when(emptyKnowledge.search(anyString(), anyInt(), isNull(), anyString())).thenReturn(List.of());
        PromptTemplateService prompts = mock(PromptTemplateService.class);
        when(prompts.render(anyString(), any(Map.class))).thenReturn(renderedPrompt("rendered prompt"));

        UnderwritingAgentOrchestrator isolated = isolatedOrchestrator(
                emptyKnowledge,
                prompts,
                new ToolRegistry(new FakeUnderwritingFactTools(DemoScenarioRepository.loadDefault())));

        UnderwritingEvaluation evaluation = isolated.evaluate(
                new EvaluationRequest(null, "P-2001", "这张低风险办公楼保单是否可以承保？"));

        assertThat(evaluation.evidence()).isEmpty();
        assertThat(evaluation.decision()).isEqualTo(Decision.MANUAL_REVIEW);
        assertThat(evaluation.summary()).contains("人工复核");
    }

    @Test
    void recordsTheFailedStepBeforePropagatingACriticalToolFailure() {
        ToolRegistry failingTools = mock(ToolRegistry.class);
        when(failingTools.invoke(ToolName.GET_POLICY, "P-MISSING"))
                .thenThrow(new ResourceNotFoundException("POLICY_NOT_FOUND", "P-MISSING"));

        UnderwritingAgentOrchestrator isolated = isolatedOrchestrator(
                mock(KnowledgeService.class),
                mock(PromptTemplateService.class),
                failingTools);

        assertThatThrownBy(() -> isolated.evaluate(
                new EvaluationRequest(null, "P-MISSING", "是否承保？")))
                .isInstanceOf(ResourceNotFoundException.class);

        assertThat(isolated.lastStepTraces()).last().satisfies(trace -> {
            assertThat(trace.step()).isEqualTo(AgentStep.BUSINESS_DATA_COLLECTION);
            assertThat(trace.status()).isEqualTo(StepStatus.FAILED);
            assertThat(trace.errorCode()).isEqualTo("POLICY_NOT_FOUND");
        });
    }

    @Test
    void degradesANonCriticalDisasterFailureWithoutTreatingUnknownRiskAsLow() {
        var scenario = DemoScenarioRepository.loadDefault().required("P-2001");
        UnderwritingFactTools factTools = mock(UnderwritingFactTools.class);
        when(factTools.getPolicy("P-2001")).thenReturn(scenario.policy());
        when(factTools.getQuotation("P-2001")).thenReturn(scenario.quotation());
        when(factTools.getUnderwritingHistory("P-2001")).thenReturn(scenario.history());
        when(factTools.getSurveyReport("P-2001")).thenReturn(scenario.survey());
        when(factTools.getDisasterRisk("P-2001")).thenThrow(new IllegalStateException("upstream timeout"));

        KnowledgeService reliableKnowledge = mock(KnowledgeService.class);
        DocumentChunk chunk = new DocumentChunk(
                "CHUNK-DEGRADED",
                "DOC-DEGRADED",
                0,
                "办公楼核保指引",
                DocumentType.RISK_GUIDE,
                "PROPERTY",
                "办公楼基础风险较低，但外部灾害数据缺失时必须人工补充核验。",
                Map.of());
        when(reliableKnowledge.search(anyString(), anyInt(), isNull(), anyString()))
                .thenReturn(List.of(new RetrievalHit(chunk, 0.9)));
        PromptTemplateService prompts = mock(PromptTemplateService.class);
        when(prompts.render(anyString(), any(Map.class)))
                .thenReturn(renderedPrompt("rendered prompt with degradation warning"));

        UnderwritingAgentOrchestrator isolated = isolatedOrchestrator(
                reliableKnowledge,
                prompts,
                new ToolRegistry(factTools));

        UnderwritingEvaluation evaluation = isolated.evaluate(
                new EvaluationRequest(null, "P-2001", "灾害平台超时时是否可以自动承保？"));

        assertThat(evaluation.evidence()).isNotEmpty();
        assertThat(evaluation.riskLevel()).isEqualTo(com.hrniux.underwriting.rule.RiskLevel.LOW);
        assertThat(evaluation.riskScore()).isEqualTo(10);
        assertThat(evaluation.decision()).isEqualTo(Decision.MANUAL_REVIEW);
        assertThat(evaluation.degradations()).singleElement().satisfies(degradation -> {
            assertThat(degradation.code()).isEqualTo("NON_CRITICAL_TOOL_UNAVAILABLE");
            assertThat(degradation.toolName()).isEqualTo(ToolName.GET_DISASTER_RISK);
            assertThat(degradation.errorCode()).isEqualTo("TOOL_CALL_FAILED");
            assertThat(degradation.decisionFloor()).isEqualTo(Decision.MANUAL_REVIEW);
        });
        assertThat(evaluation.toolTraces())
                .filteredOn(trace -> trace.toolName() == ToolName.GET_DISASTER_RISK)
                .singleElement()
                .satisfies(trace -> assertThat(trace.status()).isEqualTo(ToolCallStatus.FAILED));
        assertThat(evaluation.stepTraces())
                .filteredOn(trace -> trace.step() == AgentStep.BUSINESS_DATA_COLLECTION)
                .singleElement()
                .satisfies(trace -> {
                    assertThat(trace.status()).isEqualTo(StepStatus.DEGRADED);
                    assertThat(trace.errorCode()).isEqualTo("NON_CRITICAL_TOOL_UNAVAILABLE");
        });
        assertThat(evaluation.summary()).contains("人工复核");
        assertThat(evaluation.reasons()).anyMatch(reason -> reason.contains("灾害风险数据暂时不可用"));
        assertThat(evaluation.recommendedActions()).contains("补充并核验缺失的外部灾害风险数据");
        verify(reliableKnowledge).search(contains("UNKNOWN"), eq(4), isNull(), eq("PROPERTY"));
    }

    private UnderwritingAgentOrchestrator isolatedOrchestrator(
            KnowledgeService knowledge,
            PromptTemplateService prompts,
            ToolRegistry tools) {
        AtomicInteger sequence = new AtomicInteger();
        return new UnderwritingAgentOrchestrator(
                new SessionService(new InMemorySessionRepository()),
                knowledge,
                prompts,
                tools,
                new UnderwritingRuleEngine(),
                new DeterministicMockModelGateway("test-model"),
                new InMemoryEvaluationRepository(),
                Clock.fixed(Instant.parse("2026-07-13T06:00:00Z"), ZoneOffset.UTC),
                () -> "EVAL-" + sequence.incrementAndGet());
    }

    private RenderedPrompt renderedPrompt(String content) {
        return new RenderedPrompt(
                new PromptSnapshot("underwriting-analysis", 1, "b".repeat(64)), content);
    }
}
