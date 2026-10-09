package app.peakstone.license;

import app.peakstone.license.LicenseResult.Status;
import java.time.Clock;

/**
 * Decides whether a signed response can be trusted for <em>this</em> plugin, licence key and server
 * instance. It never looks at the network or the disk.
 */
final class Verifier {
    /** How far in the future {@code issuedAt} may be before the local clock is considered wrong. */
    static final long MAX_FUTURE_SKEW_SECONDS = 5 * 60;

    private static final int SUPPORTED_VERSION = 1;

    private final String product;
    private final String license;
    private final String instance;
    private final TrustedKeys keys;
    private final Clock clock;

    /**
     * @param license the normalised licence key
     */
    Verifier(String product, String license, String instance, TrustedKeys keys, Clock clock) {
        this.product = product;
        this.license = license;
        this.instance = instance;
        this.keys = keys;
        this.clock = clock;
    }

    /**
     * Checks the signature first, then that the payload belongs to this plugin, licence key and
     * instance, and that it is within its validity window.
     *
     * @param expectedNonce the nonce sent with the request, or {@code null} when re-checking a
     *                      cached response (a stored response cannot match a fresh nonce)
     * @return the authenticated payload; it may still say {@code valid: false}
     * @throws Rejection if the response must not be trusted
     */
    Wire.Payload authenticate(Wire.Signed signed, String expectedNonce) throws Rejection {
        byte[] payloadBytes;
        byte[] signature;
        try {
            payloadBytes = Wire.decodeBase64(signed.payload());
            signature = Wire.decodeBase64(signed.signature());
        } catch (IllegalArgumentException e) {
            throw Wire.malformed("payload or signature is not valid base64url");
        }

        if (!keys.verify(payloadBytes, signature, signed.keyId())) {
            throw new Rejection(Status.BAD_SIGNATURE, "signature does not match a trusted Peakstone key");
        }

        Wire.Payload p = Wire.Payload.parse(payloadBytes);
        if (p.v() != SUPPORTED_VERSION) {
            throw Wire.malformed("unsupported payload version " + p.v());
        }
        if (!product.equals(p.product())) {
            throw new Rejection(Status.WRONG_PRODUCT, "response is for a different product");
        }
        if (!license.equals(LicenseKeys.normalize(p.license()))) {
            throw new Rejection(Status.BAD_SIGNATURE, "response is for a different licence key");
        }
        if (!instance.equals(p.instance())) {
            throw new Rejection(Status.BAD_SIGNATURE, "response is for a different server instance");
        }
        if (expectedNonce != null && !expectedNonce.equals(p.nonce())) {
            throw new Rejection(Status.BAD_SIGNATURE, "response does not answer this request (nonce mismatch)");
        }

        long now = clock.instant().getEpochSecond();
        if (p.valid() && (p.issuedAt() == null || p.expiresAt() == null)) {
            throw Wire.malformed("valid response without issuedAt/expiresAt");
        }
        if (p.issuedAt() != null && p.issuedAt() > now + MAX_FUTURE_SKEW_SECONDS) {
            throw new Rejection(
                    Status.BAD_SIGNATURE, "response was issued in the future; check the system clock");
        }
        if (p.expiresAt() != null && p.expiresAt() <= now) {
            throw new Rejection(Status.BAD_SIGNATURE, "response has expired; check the system clock");
        }
        return p;
    }
}
