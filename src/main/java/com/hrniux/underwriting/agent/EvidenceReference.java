package com.hrniux.underwriting.agent;

import java.util.Objects;

public record EvidenceReference(
        String documentId,
        int knowledgeVersion,
        String chunkId) implements Comparable<EvidenceReference> {

    public EvidenceReference {
        Objects.requireNonNull(documentId, "documentId must not be null");
        Objects.requireNonNull(chunkId, "chunkId must not be null");
        if (knowledgeVersion < 0) {
            throw new IllegalArgumentException("knowledgeVersion must not be negative");
        }
    }

    public static EvidenceReference from(Evidence evidence) {
        return new EvidenceReference(
                evidence.documentId(),
                evidence.knowledgeVersion(),
                evidence.chunkId());
    }

    public String displayName() {
        String version = knowledgeVersion > 0 ? "v" + knowledgeVersion : "unknown";
        return documentId + "@" + version + "#" + chunkId;
    }

    @Override
    public int compareTo(EvidenceReference other) {
        return displayName().compareTo(other.displayName());
    }
}
