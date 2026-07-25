package com.hrniux.underwriting.agent;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.hrniux.underwriting.model.ModelGateway;
import com.hrniux.underwriting.model.ModelRequest;
import com.hrniux.underwriting.model.ModelResponse;
import com.hrniux.underwriting.model.ModelUnavailableException;
import com.hrniux.underwriting.prompt.PromptTemplateService;
import com.hrniux.underwriting.prompt.RenderedPrompt;
import com.hrniux.underwriting.rag.KnowledgeService;
import com.hrniux.underwriting.rag.RetrievalHit;
import com.hrniux.underwriting.rule.Decision;
import com.hrniux.underwriting.rule.RuleEvaluation;
import com.hrniux.underwriting.rule.UnderwritingContext;
import com.hrniux.underwriting.rule.UnderwritingRuleEngine;
import com.hrniux.underwriting.session.SessionRole;
import com.hrniux.underwriting.session.SessionService;
import com.hrniux.underwriting.shared.error.ResourceNotFoundException;
import com.hrniux.underwriting.tool.DisasterRiskFacts;
import com.hrniux.underwriting.tool.PolicyFacts;
import com.hrniux.underwriting.tool.QuotationFacts;
import com.hrniux.underwriting.tool.SurveyReportFacts;
import com.hrniux.underwriting.tool.ToolAttempt;
import com.hrniux.underwriting.tool.ToolCallTrace;
import com.hrniux.underwriting.tool.ToolCriticality;
import com.hrniux.underwriting.tool.ToolInvocation;
import com.hrniux.underwriting.tool.ToolName;
import com.hrniux.underwriting.tool.ToolRegistry;
import com.hrniux.underwriting.tool.UnderwritingHistoryFacts;

@Service
public class UnderwritingAgentOrchestrator {

    private static final String PROMPT_CODE = "underwriting-analysis";

    private final SessionService sessions;
    private final KnowledgeService knowledge;
    private final PromptTemplateService prompts;
    private final ToolRegistry tools;
    private final UnderwritingRuleEngine rules;
    private final ModelGateway models;
    private final EvaluationRepository evaluations;
    private final Clock clock;
    private final Supplier<String> idSupplier;
    private final ThreadLocal<List<StepTrace>> currentTraces = ThreadLocal.withInitial(ArrayList::new);

    @Autowired
    public UnderwritingAgentOrchestrator(
            SessionService sessions,
            KnowledgeService knowledge,
            PromptTemplateService prompts,
            ToolRegistry tools,
            UnderwritingRuleEngine rules,
            ModelGateway models,
            EvaluationRepository evaluations) {
        this(sessions, knowledge, prompts, tools, rules, models, evaluations, Clock.systemUTC(),
                () -> "EVAL-" + UUID.randomUUID().toString().replace("-", ""));
    }

    UnderwritingAgentOrchestrator(
            SessionService sessions,
            KnowledgeService knowledge,
            PromptTemplateService prompts,
            ToolRegistry tools,
            UnderwritingRuleEngine rules,
            ModelGateway models,
            EvaluationRepository evaluations,
            Clock clock,
            Supplier<String> idSupplier) {
        this.sessions = Objects.requireNonNull(sessions);
        this.knowledge = Objects.requireNonNull(knowledge);
        this.prompts = Objects.requireNonNull(prompts);
        this.tools = Objects.requireNonNull(tools);
        this.rules = Objects.requireNonNull(rules);
        this.models = Objects.requireNonNull(models);
        this.evaluations = Objects.requireNonNull(evaluations);
        this.clock = Objects.requireNonNull(clock);
        this.idSupplier = Objects.requireNonNull(idSupplier);
    }

    public UnderwritingEvaluation evaluate(EvaluationRequest request) {
        currentTraces.set(new ArrayList<>());
        List<ToolCallTrace> toolTraces = new ArrayList<>();

        String sessionId = runStep(AgentStep.QUESTION_UNDERSTANDING, () -> {
            String id = request.sessionId() == null
                    ? sessions.createSession().id()
                    : sessions.getSession(request.sessionId()).id();
            sessions.appendMessage(id, SessionRole.USER, request.question());
            return id;
        });

        FactBundle facts = runStep(
                AgentStep.BUSINESS_DATA_COLLECTION,
                () -> loadFacts(request.policyNo(), toolTraces),
                result -> result.degradations().isEmpty()
                        ? StepCompletion.success()
                        : StepCompletion.degraded(result.degradations().getFirst().code()));

        List<Evidence> evidence = runStep(AgentStep.KNOWLEDGE_RETRIEVAL, () -> retrieveEvidence(request, facts));

        UnderwritingContext context = runStep(AgentStep.RISK_ANALYSIS,
                () -> new UnderwritingContext(facts.policy(), facts.quotation(), facts.history(), facts.survey(),
                        facts.disaster()));

        RuleEvaluation ruleEvaluation = runStep(AgentStep.RULE_VALIDATION, () -> {
            ToolInvocation<RuleEvaluation> invocation = tools.invokeRuleValidation(
                    request.policyNo(), () -> rules.evaluate(context));
            toolTraces.add(invocation.trace());
            return applySafetyFloors(invocation.result(), evidence, facts.degradations());
        });

        ModelResponse modelResponse = runStep(AgentStep.RECOMMENDATION_GENERATION, () -> {
            RenderedPrompt rendered = prompts.render(
                    PROMPT_CODE, promptVariables(request, facts, ruleEvaluation, evidence));
            return models.generate(new ModelRequest(rendered.content(), ruleEvaluation,
                    evidence.stream().map(Evidence::excerpt).toList(),
                    facts.degradations().stream().map(DegradationNotice::message).toList(),
                    rendered.snapshot()));
        });

        Instant createdAt = clock.instant();
        UnderwritingEvaluation draft = new UnderwritingEvaluation(
                idSupplier.get(), sessionId, request.policyNo(), request.question(),
                ruleEvaluation.decision(), ruleEvaluation.riskLevel(), ruleEvaluation.riskScore(),
                modelResponse.summary(), modelResponse.reasons(), modelResponse.recommendedActions(),
                facts.degradations(), evidence, ruleEvaluation.hits(), toolTraces, lastStepTraces(), modelResponse,
                createdAt, rules.snapshot(), request.replayedFromEvaluationId());

        UnderwritingEvaluation saved = runStep(AgentStep.RESULT_PERSISTENCE, () -> {
            sessions.appendMessage(sessionId, SessionRole.ASSISTANT, modelResponse.summary());
            return evaluations.save(draft);
        });
        UnderwritingEvaluation finalized = saved.withStepTraces(lastStepTraces());
        return evaluations.save(finalized);
    }

    public UnderwritingEvaluation getEvaluation(String evaluationId) {
        return evaluations.findById(evaluationId)
                .orElseThrow(() -> new ResourceNotFoundException("EVALUATION_NOT_FOUND", evaluationId));
    }

    public List<UnderwritingEvaluation> listEvaluations() {
        return evaluations.findAll();
    }

    public List<StepTrace> lastStepTraces() {
        return List.copyOf(currentTraces.get());
    }

    private FactBundle loadFacts(String policyNo, List<ToolCallTrace> traces) {
        ToolInvocation<?> policy = tools.invoke(ToolName.GET_POLICY, policyNo);
        traces.add(policy.trace());
        ToolInvocation<?> quotation = tools.invoke(ToolName.GET_QUOTATION, policyNo);
        traces.add(quotation.trace());
        ToolInvocation<?> history = tools.invoke(ToolName.GET_UNDERWRITING_HISTORY, policyNo);
        traces.add(history.trace());
        ToolInvocation<?> survey = tools.invoke(ToolName.GET_SURVEY_REPORT, policyNo);
        traces.add(survey.trace());
        ToolAttempt<?> disasterAttempt = tools.tryInvoke(ToolName.GET_DISASTER_RISK, policyNo);
        traces.add(disasterAttempt.trace());
        DisasterRiskFacts disaster;
        List<DegradationNotice> degradations;
        if (disasterAttempt.succeeded()) {
            disaster = (DisasterRiskFacts) disasterAttempt.result();
            degradations = List.of();
        }
        else {
            if (ToolName.GET_DISASTER_RISK.criticality() != ToolCriticality.DEGRADABLE) {
                throw disasterAttempt.failure();
            }
            disaster = DisasterRiskFacts.unavailable(policyNo, LocalDate.now(clock));
            degradations = List.of(new DegradationNotice(
                    "NON_CRITICAL_TOOL_UNAVAILABLE",
                    ToolName.GET_DISASTER_RISK,
                    disasterAttempt.trace().errorCode(),
                    "灾害风险数据暂时不可用，系统未将未知风险解释为低风险，必须转人工补充核验。",
                    Decision.MANUAL_REVIEW));
        }
        return new FactBundle(
                (PolicyFacts) policy.result(),
                (QuotationFacts) quotation.result(),
                (UnderwritingHistoryFacts) history.result(),
                (SurveyReportFacts) survey.result(),
                disaster,
                degradations);
    }

    private List<Evidence> retrieveEvidence(EvaluationRequest request, FactBundle facts) {
        String query = "%s；标的用途：%s；暴雨风险：%s".formatted(
                request.question(), facts.policy().occupancy(), facts.disaster().rainstorm());
        return knowledge.search(query, 4, null, facts.policy().productCode()).stream()
                .map(this::toEvidence)
                .toList();
    }

    private Evidence toEvidence(RetrievalHit hit) {
        String content = hit.chunk().content();
        String excerpt = content.length() <= 240 ? content : content.substring(0, 240) + "…";
        return new Evidence(
                hit.chunk().documentId(),
                hit.chunk().id(),
                hit.chunk().title(),
                hit.chunk().type(),
                excerpt,
                hit.score(),
                knowledgeVersion(hit),
                hit.vectorScore(),
                hit.lexicalScore(),
                hit.mode(),
                hit.matchedTerms());
    }

    private int knowledgeVersion(RetrievalHit hit) {
        String rawVersion = hit.chunk().metadata().get("knowledgeVersion");
        if (rawVersion == null) {
            return 0;
        }
        try {
            return Math.max(0, Integer.parseInt(rawVersion));
        }
        catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private RuleEvaluation applySafetyFloors(
            RuleEvaluation rulesResult,
            List<Evidence> evidence,
            List<DegradationNotice> degradations) {
        Decision decision = rulesResult.decision();
        if (evidence.isEmpty()) {
            decision = Decision.strongest(decision, Decision.MANUAL_REVIEW);
        }
        for (DegradationNotice degradation : degradations) {
            decision = Decision.strongest(decision, degradation.decisionFloor());
        }
        return new RuleEvaluation(decision, rulesResult.riskLevel(), rulesResult.riskScore(), rulesResult.hits());
    }

    private Map<String, Object> promptVariables(
            EvaluationRequest request,
            FactBundle facts,
            RuleEvaluation ruleEvaluation,
            List<Evidence> evidence) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("question", request.question());
        values.put("policyFacts", facts.policy());
        values.put("quotationFacts", facts.quotation());
        values.put("historyFacts", facts.history());
        values.put("surveyFacts", facts.survey());
        values.put("disasterFacts", facts.disaster());
        values.put("ruleResults", ruleEvaluation);
        values.put("knowledgeEvidence", evidence.isEmpty() ? "未检索到可靠证据" : evidence);
        values.put("dataQualityWarnings", facts.degradations().isEmpty() ? "无" : facts.degradations());
        return values;
    }

    private <T> T runStep(AgentStep step, Supplier<T> action) {
        return runStep(step, action, ignored -> StepCompletion.success());
    }

    private <T> T runStep(
            AgentStep step,
            Supplier<T> action,
            Function<T, StepCompletion> completionResolver) {
        Instant startedAt = clock.instant();
        long startedNanos = System.nanoTime();
        try {
            T result = action.get();
            StepCompletion completion = completionResolver.apply(result);
            currentTraces.get().add(new StepTrace(step, completion.status(), startedAt,
                    elapsedMillis(startedNanos), completion.errorCode()));
            return result;
        }
        catch (RuntimeException error) {
            currentTraces.get().add(new StepTrace(step, StepStatus.FAILED, startedAt,
                    elapsedMillis(startedNanos), errorCode(error)));
            throw error;
        }
    }

    private String errorCode(RuntimeException error) {
        if (error instanceof ResourceNotFoundException notFound) {
            return notFound.errorCode();
        }
        if (error instanceof ModelUnavailableException unavailable) {
            return unavailable.errorCode();
        }
        return "AGENT_STEP_FAILED";
    }

    private long elapsedMillis(long startedNanos) {
        return Math.max(0, (System.nanoTime() - startedNanos) / 1_000_000);
    }

    private record FactBundle(
            PolicyFacts policy,
            QuotationFacts quotation,
            UnderwritingHistoryFacts history,
            SurveyReportFacts survey,
            DisasterRiskFacts disaster,
            List<DegradationNotice> degradations) {

        private FactBundle {
            degradations = List.copyOf(degradations);
        }
    }

    private record StepCompletion(StepStatus status, String errorCode) {

        private static StepCompletion success() {
            return new StepCompletion(StepStatus.SUCCESS, null);
        }

        private static StepCompletion degraded(String errorCode) {
            return new StepCompletion(StepStatus.DEGRADED, errorCode);
        }
    }
}
