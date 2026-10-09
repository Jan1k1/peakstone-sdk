package app.peakstone.license;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.KeyPair;
import java.security.PublicKey;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;

class TrustedKeysTest {
    // RFC 8032 section 7.1, TEST 1 (empty message)
    private static final byte[] RFC_PUBLIC = HexFormat.of().parseHex("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a");
    private static final byte[] RFC_SIGNATURE = HexFormat.of().parseHex(
            "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b");

    @Test
    void verifiesAnRfc8032KnownAnswer() {
        PublicKey key = TrustedKeys.parse(Base64.getEncoder().encodeToString(RFC_PUBLIC));
        TrustedKeys keys = new TrustedKeys(List.of(new TrustedKeys.Entry(null, key)));

        assertTrue(keys.verify(new byte[0], RFC_SIGNATURE, "ps-1"));
        assertFalse(keys.verify(new byte[] {1}, RFC_SIGNATURE, "ps-1"));
    }

    @Test
    void rawKeyBuildsTheSameKeyTheJdkWouldEncode() {
        KeyPair pair = FakePeakstone.newKeyPair();

        PublicKey parsed = TrustedKeys.parse(FakePeakstone.rawPublicKeyBase64(pair));

        assertArrayEquals(pair.getPublic().getEncoded(), parsed.getEncoded());
    }

    @Test
    void acceptsEveryEncodingOfTheSameKey() {
        KeyPair pair = FakePeakstone.newKeyPair();
        byte[] spki = pair.getPublic().getEncoded();
        byte[] raw = java.util.Arrays.copyOfRange(spki, spki.length - 32, spki.length);

        String[] encodings = {
            Base64.getEncoder().encodeToString(raw),
            Base64.getEncoder().withoutPadding().encodeToString(raw),
            Base64.getUrlEncoder().encodeToString(raw),
            Base64.getUrlEncoder().withoutPadding().encodeToString(raw),
            Base64.getEncoder().encodeToString(spki),
            Base64.getUrlEncoder().withoutPadding().encodeToString(spki),
            "  " + Base64.getEncoder().encodeToString(raw) + "\n"
        };

        for (String encoding : encodings) {
            assertArrayEquals(spki, TrustedKeys.parse(encoding).getEncoded(), encoding);
        }
        assertTrue(Base64.getEncoder().encodeToString(spki).startsWith("MCowBQYDK2VwAyEA"));
    }

    @Test
    void rejectsKeysOfTheWrongSizeOrKind() {
        byte[] sixtyFour = new byte[64];
        byte[] wrongHeader = new byte[44]; // right length for SPKI, wrong prefix

        assertThrows(IllegalArgumentException.class, () -> TrustedKeys.parse(Base64.getEncoder().encodeToString(new byte[31])));
        assertThrows(IllegalArgumentException.class, () -> TrustedKeys.parse(Base64.getEncoder().encodeToString(sixtyFour)));
        assertThrows(IllegalArgumentException.class, () -> TrustedKeys.parse(Base64.getEncoder().encodeToString(wrongHeader)));
        assertThrows(IllegalArgumentException.class, () -> TrustedKeys.parse("???"));
    }

    @Test
    void base64DecodingAcceptsBothAlphabetsWithOrWithoutPadding() {
        byte[] bytes = {(byte) 0xfb, (byte) 0xff, (byte) 0xbf, 0x01};

        assertArrayEquals(bytes, Wire.decodeBase64("+/+/AQ=="));
        assertArrayEquals(bytes, Wire.decodeBase64("+/+/AQ"));
        assertArrayEquals(bytes, Wire.decodeBase64("-_-_AQ"));
        assertArrayEquals(bytes, Wire.decodeBase64("-_-_AQ=="));
        assertEquals("-_-_AQ", Wire.encodeBase64Url(bytes));
        assertThrows(IllegalArgumentException.class, () -> Wire.decodeBase64("a"));
        assertThrows(IllegalArgumentException.class, () -> Wire.decodeBase64("ab$d"));
    }

    @Test
    void keyRegisteredUnderAnIdOnlyVouchesForThatId() {
        KeyPair pair = FakePeakstone.newKeyPair();
        byte[] data = {1, 2, 3};
        byte[] signature = FakePeakstone.sign(pair.getPrivate(), data);
        PublicKey key = TrustedKeys.parse(FakePeakstone.rawPublicKeyBase64(pair));

        TrustedKeys named = new TrustedKeys(List.of(new TrustedKeys.Entry("ps-1", key)));
        TrustedKeys anonymous = new TrustedKeys(List.of(new TrustedKeys.Entry(null, key)));

        assertTrue(named.verify(data, signature, "ps-1"));
        assertFalse(named.verify(data, signature, "ps-2"));
        assertFalse(named.verify(data, signature, null));
        assertTrue(anonymous.verify(data, signature, "ps-2"));
        assertTrue(anonymous.verify(data, signature, null));
        assertFalse(anonymous.verify(new byte[] {9}, signature, "ps-1"));
    }
}
