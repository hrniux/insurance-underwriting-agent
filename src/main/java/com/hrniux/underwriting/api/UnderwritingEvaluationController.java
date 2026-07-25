package com.hrniux.underwriting.api;

import java.net.URI;
import java.util.List;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.hrniux.underwriting.agent.EvaluationSubmissionResult;
import com.hrniux.underwriting.agent.EvaluationSubmissionService;
import com.hrniux.underwriting.agent.DecisionDrift;
import com.hrniux.underwriting.agent.DecisionReplayResult;
import com.hrniux.underwriting.agent.DecisionReplayService;
import com.hrniux.underwriting.agent.UnderwritingAgentOrchestrator;
import com.hrniux.underwriting.agent.UnderwritingEvaluation;
import com.hrniux.underwriting.api.ApiDtos.EvaluationApiRequest;
import com.hrniux.underwriting.report.UnderwritingMarkdownReportService;
import com.hrniux.underwriting.review.HumanReviewService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/underwriting/evaluations")
public class UnderwritingEvaluationController {

    private final UnderwritingAgentOrchestrator orchestrator;
    private final EvaluationSubmissionService submissions;
    private final UnderwritingMarkdownReportService reportService;
    private final HumanReviewService reviews;
    private final DecisionReplayService replays;

    public UnderwritingEvaluationController(
            UnderwritingAgentOrchestrator orchestrator,
            EvaluationSubmissionService submissions,
            UnderwritingMarkdownReportService reportService,
            HumanReviewService reviews,
            DecisionReplayService replays) {
        this.orchestrator = orchestrator;
        this.submissions = submissions;
        this.reportService = reportService;
        this.reviews = reviews;
        this.replays = replays;
    }

    @PostMapping
    public ResponseEntity<UnderwritingEvaluation> evaluate(
            @RequestHeader(name = EvaluationSubmissionService.IDEMPOTENCY_HEADER, required = false)
            String idempotencyKey,
            @Valid @RequestBody EvaluationApiRequest request) {
        EvaluationSubmissionResult result = submissions.submit(request.toDomain(), idempotencyKey);
        UnderwritingEvaluation evaluation = result.evaluation();
        ResponseEntity.BodyBuilder response = result.replayed()
                ? ResponseEntity.ok()
                : ResponseEntity.created(URI.create("/api/v1/underwriting/evaluations/" + evaluation.id()));
        return response.header(EvaluationSubmissionService.REPLAY_HEADER, Boolean.toString(result.replayed()))
                .body(evaluation);
    }

    @GetMapping("/{evaluationId}")
    public UnderwritingEvaluation get(@PathVariable String evaluationId) {
        return orchestrator.getEvaluation(evaluationId);
    }

    @GetMapping(value = "/{evaluationId}/report", produces = "text/markdown;charset=UTF-8")
    public ResponseEntity<String> report(@PathVariable String evaluationId) {
        UnderwritingEvaluation evaluation = orchestrator.getEvaluation(evaluationId);
        String filename = "underwriting-report-" + evaluation.id() + ".md";
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/markdown;charset=UTF-8"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .body(reportService.render(evaluation, reviews.find(evaluationId)));
    }

    @GetMapping
    public List<UnderwritingEvaluation> list() {
        return orchestrator.listEvaluations();
    }

    @PostMapping("/{evaluationId}/replays")
    public ResponseEntity<DecisionReplayResult> replay(
            @PathVariable String evaluationId,
            @RequestHeader(name = EvaluationSubmissionService.IDEMPOTENCY_HEADER, required = false)
            String idempotencyKey) {
        DecisionReplayResult result = replays.replay(evaluationId, idempotencyKey);
        ResponseEntity.BodyBuilder response = result.idempotencyReplayed()
                ? ResponseEntity.ok()
                : ResponseEntity.created(URI.create(
                        "/api/v1/underwriting/evaluations/" + result.replay().id()));
        return response.header(
                        EvaluationSubmissionService.REPLAY_HEADER,
                        Boolean.toString(result.idempotencyReplayed()))
                .body(result);
    }

    @GetMapping("/{evaluationId}/drift")
    public DecisionDrift drift(@PathVariable String evaluationId) {
        return replays.drift(evaluationId);
    }
}
