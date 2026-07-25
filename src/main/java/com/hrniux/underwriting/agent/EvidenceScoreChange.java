package com.hrniux.underwriting.agent;

import java.util.Objects;

public record EvidenceScoreChange(
        EvidenceReference evidence,
        double baselineScore,
        double replayScore,
        double scoreDelta,
        boolean retrievalExplanationChanged) {

    public EvidenceScoreChange {
        Objects.requireNonNull(evidence, "evidence must not be null");
    }
}
