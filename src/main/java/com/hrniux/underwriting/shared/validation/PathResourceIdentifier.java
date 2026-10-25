package com.hrniux.underwriting.shared.validation;

public final class PathResourceIdentifier {

    private PathResourceIdentifier() {
    }

    public static String require(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        String normalized = value.trim();
        if (normalized.contains("/") || normalized.contains("\\")
                || normalized.equals(".") || normalized.equals("..")
                || normalized.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(field + " must be an addressable path segment");
        }
        return normalized;
    }
}
