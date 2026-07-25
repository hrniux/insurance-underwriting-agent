package com.hrniux.underwriting.agent;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Pattern;

import com.hrniux.underwriting.shared.error.InvalidRequestException;

public final class EvaluationIdempotency {

    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");

    private EvaluationIdempotency() {
    }

    public static String normalizeKey(String rawKey) {
        if (rawKey == null || rawKey.isBlank()) {
            return null;
        }
        String key = rawKey.trim();
        if (!KEY.matcher(key).matches()) {
            throw new InvalidRequestException(
                    "INVALID_IDEMPOTENCY_KEY",
                    "Idempotency-Key must be 1-128 characters using letters, digits, dot, underscore, colon or hyphen");
        }
        return key;
    }

    public static String fingerprint(EvaluationRequest request) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            update(digest, request.sessionId());
            update(digest, request.policyNo());
            update(digest, request.question());
            update(digest, request.replayedFromEvaluationId());
            return HexFormat.of().formatHex(digest.digest());
        }
        catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is not available", impossible);
        }
    }

    private static void update(MessageDigest digest, String value) {
        if (value == null) {
            digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(-1).array());
            return;
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }
}
