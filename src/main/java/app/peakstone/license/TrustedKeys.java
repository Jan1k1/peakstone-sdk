package app.peakstone.license;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

/** The Ed25519 public keys Peakstone responses are accepted from. */
final class TrustedKeys {
    /** DER prefix of an X.509 SubjectPublicKeyInfo holding a raw 32-byte Ed25519 key. */
    private static final byte[] SPKI_HEADER = HexFormat.of().parseHex("302a300506032b6570032100");

    private static final int RAW_KEY_LENGTH = 32;

    /** A trusted key. A {@code null} id means "accept any keyId in the response". */
    record Entry(String id, PublicKey key) {}

    private final List<Entry> entries;

    TrustedKeys(List<Entry> entries) {
        this.entries = List.copyOf(entries);
    }

    /**
     * Whether {@code signature} is a valid Ed25519 signature over {@code payload} by a key that
     * is trusted for {@code keyId} (the one registered under that id, or any key registered
     * without an id).
     */
    boolean verify(byte[] payload, byte[] signature, String keyId) {
        for (Entry entry : entries) {
            if (entry.id() != null && !entry.id().equals(keyId)) {
                continue;
            }
            try {
                Signature verifier = Signature.getInstance("Ed25519");
                verifier.initVerify(entry.key());
                verifier.update(payload);
                if (verifier.verify(signature)) {
                    return true;
                }
            } catch (GeneralSecurityException e) {
                // wrong signature length or similar: this key does not vouch for it
            }
        }
        return false;
    }

    /**
     * Parses a public key given as base64 or base64url (padding optional). Both the raw 32-byte
     * key and its X.509 SubjectPublicKeyInfo encoding ({@code MCowBQYDK2VwAyEA...}) are accepted.
     *
     * @throws IllegalArgumentException if the text is not an Ed25519 public key
     */
    static PublicKey parse(String encoded) {
        byte[] bytes;
        try {
            bytes = Wire.decodeBase64(encoded);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("public key is not valid base64", e);
        }
        byte[] spki;
        if (bytes.length == RAW_KEY_LENGTH) {
            spki = new byte[SPKI_HEADER.length + RAW_KEY_LENGTH];
            System.arraycopy(SPKI_HEADER, 0, spki, 0, SPKI_HEADER.length);
            System.arraycopy(bytes, 0, spki, SPKI_HEADER.length, RAW_KEY_LENGTH);
        } else if (bytes.length == SPKI_HEADER.length + RAW_KEY_LENGTH
                && Arrays.equals(bytes, 0, SPKI_HEADER.length, SPKI_HEADER, 0, SPKI_HEADER.length)) {
            spki = bytes;
        } else {
            throw new IllegalArgumentException(
                    "public key must be a raw 32-byte Ed25519 key (or its X.509 encoding) in base64");
        }
        try {
            return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(spki));
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("not a valid Ed25519 public key", e);
        }
    }
}
