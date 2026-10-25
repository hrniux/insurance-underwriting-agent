package com.hrniux.underwriting.review;

import java.time.Instant;

import com.hrniux.underwriting.rule.Decision;
import com.hrniux.underwriting.rule.RiskLevel;

public record ReviewWorklistItem(
        String id,
        String policyNo,
        Decision decision,
        RiskLevel riskLevel,
        int riskScore,
        Instant createdAt,
        ReviewWorklistStatus status,
        HumanReviewOutcome reviewOutcome,
        String reviewerId,
        Instant reviewedAt,
        String replayedFromEvaluationId) {
}
