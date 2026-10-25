package com.hrniux.underwriting.review;

public enum ReviewWorklistStatus {
    UNREVIEWED,
    INFORMATION_REQUESTED,
    REVIEWED;

    static ReviewWorklistStatus from(HumanReview review) {
        if (review == null) {
            return UNREVIEWED;
        }
        return review.outcome() == HumanReviewOutcome.MORE_INFORMATION_REQUIRED
                ? INFORMATION_REQUESTED : REVIEWED;
    }
}
