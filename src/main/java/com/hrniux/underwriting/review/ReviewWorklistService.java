package com.hrniux.underwriting.review;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.hrniux.underwriting.agent.EvaluationRepository;
import com.hrniux.underwriting.agent.UnderwritingEvaluation;
import com.hrniux.underwriting.rule.Decision;

@Service
public class ReviewWorklistService {

    private static final Comparator<ReviewWorklistItem> PRIORITY =
            Comparator.comparingInt(ReviewWorklistItem::riskScore).reversed()
                    .thenComparing(ReviewWorklistItem::createdAt)
                    .thenComparing(ReviewWorklistItem::id);

    private final EvaluationRepository evaluations;
    private final HumanReviewRepository reviews;

    public ReviewWorklistService(EvaluationRepository evaluations, HumanReviewRepository reviews) {
        this.evaluations = evaluations;
        this.reviews = reviews;
    }

    public ReviewWorklistPage query(
            ReviewWorklistStatus status, Decision decision, String policyNo, int page, int size) {
        if (page < 0 || size < 1 || size > 50) {
            throw new IllegalArgumentException("page must be nonnegative and size must be between 1 and 50");
        }
        String normalizedPolicy = policyNo == null ? "" : policyNo.trim().toUpperCase(Locale.ROOT);
        if (normalizedPolicy.length() > 64) {
            throw new IllegalArgumentException("policyNo must be at most 64 characters");
        }
        Map<String, HumanReview> reviewsByEvaluation = new HashMap<>();
        reviews.findAll().forEach(review -> reviewsByEvaluation.put(review.evaluationId(), review));
        List<ReviewWorklistItem> matching = evaluations.findAll().stream()
                .filter(evaluation -> normalizedPolicy.isEmpty()
                        || evaluation.policyNo().toUpperCase(Locale.ROOT).equals(normalizedPolicy))
                .filter(evaluation -> decision == null || evaluation.decision() == decision)
                .map(evaluation -> item(evaluation, reviewsByEvaluation.get(evaluation.id())))
                .filter(item -> status == null || item.status() == status)
                .sorted(PRIORITY)
                .toList();
        int total = matching.size();
        long offset = (long) page * size;
        List<ReviewWorklistItem> items = offset >= total ? List.of()
                : matching.subList((int) offset, (int) Math.min(offset + size, total));
        return new ReviewWorklistPage(items, page, size, total, (int) ((total + (long) size - 1) / size));
    }

    private ReviewWorklistItem item(UnderwritingEvaluation evaluation, HumanReview review) {
        return new ReviewWorklistItem(evaluation.id(), evaluation.policyNo(), evaluation.decision(),
                evaluation.riskLevel(), evaluation.riskScore(), evaluation.createdAt(), ReviewWorklistStatus.from(review),
                review == null ? null : review.outcome(), review == null ? null : review.reviewerId(),
                review == null ? null : review.reviewedAt(), evaluation.replayedFromEvaluationId());
    }
}
