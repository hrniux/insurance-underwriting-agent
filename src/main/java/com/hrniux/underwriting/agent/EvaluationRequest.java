package com.hrniux.underwriting.agent;

public record EvaluationRequest(
        String sessionId,
        String policyNo,
        String question,
        String replayedFromEvaluationId) {

    public EvaluationRequest {
        policyNo = requireText(policyNo, "policyNo");
        question = requireText(question, "question");
        sessionId = sessionId == null || sessionId.isBlank() ? null : sessionId.trim();
        replayedFromEvaluationId = replayedFromEvaluationId == null || replayedFromEvaluationId.isBlank()
                ? null
                : replayedFromEvaluationId.trim();
    }

    public EvaluationRequest(String sessionId, String policyNo, String question) {
        this(sessionId, policyNo, question, null);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
