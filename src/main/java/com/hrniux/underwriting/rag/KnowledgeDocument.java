package com.hrniux.underwriting.rag;

import java.util.Map;
import java.util.Objects;

import com.hrniux.underwriting.shared.validation.PathResourceIdentifier;

public record KnowledgeDocument(
        String id,
        String title,
        DocumentType type,
        String productCode,
        String content,
        Map<String, String> metadata) {

    public KnowledgeDocument {
        id = PathResourceIdentifier.require(id, "id");
        title = requireText(title, "title");
        Objects.requireNonNull(type, "type must not be null");
        productCode = requireText(productCode, "productCode").toUpperCase(java.util.Locale.ROOT);
        content = requireText(content, "content");
        metadata = Map.copyOf(Objects.requireNonNull(metadata, "metadata must not be null"));
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
