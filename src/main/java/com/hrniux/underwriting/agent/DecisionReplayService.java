package com.hrniux.underwriting.agent;

import java.util.Locale;

import org.springframework.stereotype.Service;

import com.hrniux.underwriting.shared.error.InvalidRequestException;

import io.micrometer.core.instrument.MeterRegistry;

@Service
public class DecisionReplayService {

    private static final String REPLAY_METRIC = "underwriting.decision.replays";

    private final UnderwritingAgentOrchestrator orchestrator;
    private final EvaluationSubmissionService submissions;
    private final DecisionDriftService driftService;
    private final MeterRegistry metrics;

    public DecisionReplayService(
            UnderwritingAgentOrchestrator orchestrator,
            EvaluationSubmissionService submissions,
            DecisionDriftService driftService,
            MeterRegistry metrics) {
        this.orchestrator = orchestrator;
        this.submissions = submissions;
        this.driftService = driftService;
        this.metrics = metrics;
    }

    public DecisionReplayResult replay(String baselineEvaluationId, String idempotencyKey) {
        UnderwritingEvaluation baseline = orchestrator.getEvaluation(baselineEvaluationId);
        EvaluationRequest request = new EvaluationRequest(
                null,
                baseline.policyNo(),
                baseline.question(),
                baseline.id());
        EvaluationSubmissionResult submitted = submissions.submit(request, idempotencyKey);
        UnderwritingEvaluation replay = submitted.evaluation();
        DecisionDrift drift = driftService.compare(baseline, replay);
        metrics.counter(
                REPLAY_METRIC,
                "severity", drift.severity().name().toLowerCase(Locale.ROOT),
                "outcome", submitted.replayed() ? "replayed" : "created").increment();
        return new DecisionReplayResult(baseline, replay, drift, submitted.replayed());
    }

    public DecisionDrift drift(String replayEvaluationId) {
        UnderwritingEvaluation replay = orchestrator.getEvaluation(replayEvaluationId);
        if (!replay.replay()) {
            throw new InvalidRequestException(
                    "EVALUATION_NOT_REPLAY",
                    "Evaluation was not created by the decision replay endpoint: " + replayEvaluationId);
        }
        UnderwritingEvaluation baseline = orchestrator.getEvaluation(replay.replayedFromEvaluationId());
        return driftService.compare(baseline, replay);
    }
}
