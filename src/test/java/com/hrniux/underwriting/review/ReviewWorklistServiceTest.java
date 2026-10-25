package com.hrniux.underwriting.review;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.hrniux.underwriting.agent.InMemoryEvaluationRepository;
import com.hrniux.underwriting.agent.UnderwritingEvaluation;
import com.hrniux.underwriting.model.ModelResponse;
import com.hrniux.underwriting.rule.Decision;
import com.hrniux.underwriting.rule.RiskLevel;

class ReviewWorklistServiceTest {

    @Test
    void joinsReviewsByEvaluationIdAndKeepsApprovedAndInformationRequestedCasesDistinct() {
        var evaluations = new InMemoryEvaluationRepository();
        var reviews = new InMemoryHumanReviewRepository();
        var service = new ReviewWorklistService(evaluations, reviews);
        evaluations.save(evaluation("EVAL-A"));
        evaluations.save(evaluation("EVAL-B"));
        evaluations.save(evaluation("EVAL-C"));
        reviews.create(review("EVAL-A", HumanReviewOutcome.APPROVED));
        reviews.create(review("EVAL-B", HumanReviewOutcome.MORE_INFORMATION_REQUIRED));
        reviews.create(review("EVAL-UNRELATED", HumanReviewOutcome.REJECTED));

        ReviewWorklistPage all = service.query(null, null, null, 0, 10);
        assertThat(all.items()).extracting(ReviewWorklistItem::status).containsExactly(
                ReviewWorklistStatus.REVIEWED, ReviewWorklistStatus.INFORMATION_REQUESTED,
                ReviewWorklistStatus.UNREVIEWED);
        assertThat(service.query(ReviewWorklistStatus.REVIEWED, Decision.MANUAL_REVIEW, " p-1 ", 0, 10).items())
                .extracting(ReviewWorklistItem::id).containsExactly("EVAL-A");
        assertThat(evaluations.findAll()).hasSize(3);
        assertThat(reviews.findAll()).hasSize(3);
        assertThat(evaluations.findById("EVAL-A")).isPresent().get()
                .extracting(UnderwritingEvaluation::decision).isEqualTo(Decision.MANUAL_REVIEW);
    }

    private UnderwritingEvaluation evaluation(String id) {
        return new UnderwritingEvaluation(id, "SESSION", "P-1", "是否可承保？", Decision.MANUAL_REVIEW,
                RiskLevel.HIGH, 70, "人工复核", List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), new ModelResponse("人工复核", List.of(), List.of(), "mock", "mock", 1, false),
                Instant.parse("2026-01-01T00:00:00Z"));
    }

    private HumanReview review(String evaluationId, HumanReviewOutcome outcome) {
        return new HumanReview("REVIEW-" + evaluationId, evaluationId, "UW-1", outcome,
                AgentReviewRelationship.RESOLVED_MANUAL_REVIEW, "人工处理记录", List.of(),
                Instant.parse("2026-01-02T00:00:00Z"));
    }
}
