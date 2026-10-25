package com.hrniux.underwriting.prompt;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;

import com.hrniux.underwriting.shared.validation.PathResourceIdentifier;

public record PromptTemplateVersion(
        String code,
        int version,
        String body,
        Set<String> requiredVariables,
        boolean active,
        Instant createdAt) {

    public PromptTemplateVersion {
        code = PathResourceIdentifier.require(code, "code");
        if (version <= 0) {
            throw new IllegalArgumentException("version must be positive");
        }
        body = requireText(body, "body");
        requiredVariables = Set.copyOf(Objects.requireNonNull(
                requiredVariables, "requiredVariables must not be null"));
        Objects.requireNonNull(createdAt, "createdAt must not be null");
    }

    public PromptTemplateVersion withActive(boolean newActive) {
        return new PromptTemplateVersion(code, version, body, requiredVariables, newActive, createdAt);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
