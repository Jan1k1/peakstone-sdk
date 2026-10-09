package app.peakstone.license;

import app.peakstone.license.LicenseResult.Status;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/** The Peakstone wire format: the signed response envelope and its decoded payload. */
final class Wire {
    /** Latest instant accepted for any timestamp (9999-12-31T23:59:59Z). Keeps Instant conversion safe. */
    private static final long MAX_TIMESTAMP = 253_402_300_799L;

    private Wire() {}

    /** Decodes base64 or base64url, with or without padding. */
    static byte[] decodeBase64(String text) {
        return Base64.getUrlDecoder().decode(text.strip().replace('+', '-').replace('/', '_'));
    }

    static String encodeBase64Url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * The response envelope: {@code {"payload": ..., "signature": ..., "keyId": ...}}. The same
     * JSON is what gets cached on disk. The payload is kept in its encoded form because the
     * signature is over the decoded bytes exactly as the server produced them.
     */
    record Signed(String payload, String signature, String keyId) {
        static Signed parse(String json) throws Rejection {
            Map<String, Object> m = object(json);
            return new Signed(
                    string(m, "payload", true), string(m, "signature", true), string(m, "keyId", false));
        }

        String toJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("payload", payload);
            m.put("signature", signature);
            m.put("keyId", keyId);
            return Json.object(m);
        }

        /** The payload contains the licence key, so it is deliberately left out. */
        @Override
        public String toString() {
            return "Signed[keyId=" + keyId + "]";
        }
    }

    /**
     * The decoded, signed payload. Timestamps are unix seconds. {@code license}, {@code instance}
     * and {@code nonce} are echoes of the request that the SDK checks for equality.
     */
    record Payload(
            long v,
            boolean valid,
            String status,
            String reason,
            String product,
            String license,
            String instance,
            String nonce,
            String plan,
            Long issuedAt,
            Long expiresAt,
            Long periodEnd) {

        static Payload parse(byte[] utf8) throws Rejection {
            String text;
            try {
                text = StandardCharsets.UTF_8
                        .newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(utf8))
                        .toString();
            } catch (CharacterCodingException e) {
                throw malformed("payload is not valid UTF-8");
            }
            Map<String, Object> m = object(text);
            Long version = integer(m, "v", true);
            Object valid = m.get("valid");
            if (!(valid instanceof Boolean isValid)) {
                throw malformed("field \"valid\" must be a boolean");
            }
            return new Payload(
                    version,
                    isValid,
                    string(m, "status", false),
                    string(m, "reason", false),
                    string(m, "product", true),
                    string(m, "license", true),
                    string(m, "instance", true),
                    string(m, "nonce", false),
                    string(m, "plan", false),
                    timestamp(m, "issuedAt"),
                    timestamp(m, "expiresAt"),
                    timestamp(m, "periodEnd"));
        }

        /** The payload contains the licence key, so only harmless fields are shown. */
        @Override
        public String toString() {
            return "Payload[valid=" + valid + ", status=" + status + ", product=" + product + "]";
        }
    }

    static Rejection malformed(String message) {
        return new Rejection(Status.MALFORMED, message);
    }

    private static Map<String, Object> object(String json) throws Rejection {
        try {
            return Json.parseObject(json);
        } catch (IllegalArgumentException e) {
            throw malformed("invalid JSON: " + e.getMessage());
        }
    }

    private static String string(Map<String, Object> m, String key, boolean required) throws Rejection {
        Object v = m.get(key);
        if (v == null) {
            if (required) {
                throw malformed("missing field \"" + key + "\"");
            }
            return null;
        }
        if (v instanceof String s) {
            return s;
        }
        throw malformed("field \"" + key + "\" must be a string");
    }

    private static Long integer(Map<String, Object> m, String key, boolean required) throws Rejection {
        Object v = m.get(key);
        if (v == null) {
            if (required) {
                throw malformed("missing field \"" + key + "\"");
            }
            return null;
        }
        if (v instanceof Long n) {
            return n;
        }
        throw malformed("field \"" + key + "\" must be an integer");
    }

    private static Long timestamp(Map<String, Object> m, String key) throws Rejection {
        Long n = integer(m, key, false);
        if (n != null && (n < 0 || n > MAX_TIMESTAMP)) {
            throw malformed("field \"" + key + "\" is out of range");
        }
        return n;
    }
}
