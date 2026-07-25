package com.hrniux.underwriting.rule;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

public record RuleSetSnapshot(
        String version,
        String sha256,
        int ruleCount) {

    private static final String LEGACY_VERSION = "legacy-unavailable";
    private static final String LEGACY_FINGERPRINT = "unavailable";

    public RuleSetSnapshot {
        if (version == null || version.isBlank()) {
            throw new IllegalArgumentException("version must not be blank");
        }
        version = version.trim();
        Objects.requireNonNull(sha256, "sha256 must not be null");
        if (ruleCount < 0) {
            throw new IllegalArgumentException("ruleCount must not be negative");
        }
        if (ruleCount > 0 && !sha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("sha256 must be a lowercase SHA-256 value");
        }
    }

    public static RuleSetSnapshot capture(String version, int baseScore, List<String> manifest) {
        Objects.requireNonNull(manifest, "manifest must not be null");
        if (version == null || version.isBlank()) {
            throw new IllegalArgumentException("version must not be blank");
        }
        if (manifest.isEmpty()) {
            throw new IllegalArgumentException("manifest must not be empty");
        }
        String canonical = "version=" + version.trim()
                + "\nbaseScore=" + baseScore
                + "\n" + String.join("\n", manifest);
        return new RuleSetSnapshot(version, sha256(canonical), manifest.size());
    }

    public static RuleSetSnapshot legacy() {
        return new RuleSetSnapshot(LEGACY_VERSION, LEGACY_FINGERPRINT, 0);
    }

    public boolean available() {
        return ruleCount > 0;
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        }
        catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is not available", impossible);
        }
    }
}
