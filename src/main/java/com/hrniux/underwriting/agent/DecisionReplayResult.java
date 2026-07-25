package com.hrniux.underwriting.agent;

import java.util.Objects;

public record DecisionReplayResult(
        UnderwritingEvaluation baseline,
        UnderwritingEvaluation replay,
        DecisionDrift drift,
        boolean idempotencyReplayed) {

    public DecisionReplayResult {
        Objects.requireNonNull(baseline, "baseline must not be null");
        Objects.requireNonNull(replay, "replay must not be null");
        Objects.requireNonNull(drift, "drift must not be null");
    }
}
