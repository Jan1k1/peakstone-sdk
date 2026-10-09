package app.peakstone.license;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.net.URI;
import java.nio.file.Path;
import java.security.KeyPair;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class BuilderTest {
    private static final String KEY = "PS-7K3M-9QXA-2HDF-W8ZN";
    private static final KeyPair PAIR = FakePeakstone.newKeyPair();
    private static final String PUBLIC_KEY = FakePeakstone.rawPublicKeyBase64(PAIR);

    private static PeakstoneLicense.Builder valid() {
        return PeakstoneLicense.builder()
                .product("my-plugin")
                .key(KEY)
                .publicKey("ps-1", PUBLIC_KEY)
                .dataDirectory(Path.of("data"));
    }

    @Test
    void minimalConfigurationBuilds() {
        assertNotNull(valid().build());
    }

    @Test
    void sdkVersionMatchesThePom() {
        String pomVersion = System.getProperty("sdk.version");
        assumeTrue(pomVersion != null, "only set when run through Maven");

        assertEquals(pomVersion, PeakstoneLicense.VERSION);
    }

    // ---- product ----------------------------------------------------------------------------

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t"})
    void blankProductIsRejected(String product) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> valid().product(product).build());
        assertEquals("product must not be blank", e.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {"my plugin", "my-plugin\r\nx-evil: 1", "-leading-dash", "plugin/../x", "ünicode"})
    void productThatIsNotASlugIsRejected(String product) {
        assertThrows(IllegalArgumentException.class, () -> valid().product(product).build());
    }

    @ParameterizedTest
    @ValueSource(strings = {"my-plugin", "a", "Plugin_2.0", "9lives"})
    void slugsAreAccepted(String product) {
        assertDoesNotThrow(() -> valid().product(product).build());
    }

    @Test
    void slugLengthLimitIsSixtyFourCharacters() {
        assertDoesNotThrow(() -> valid().product("x".repeat(64)).build());
        assertThrows(IllegalArgumentException.class, () -> valid().product("x".repeat(65)).build());
    }

    // ---- licence key ------------------------------------------------------------------------

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\n"})
    void missingKeyIsRejected(String key) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> valid().key(key).build());
        assertEquals("licence key is missing", e.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "PS-7K3M-9QXA-2HDF",            // too short
        "PS-7K3M-9QXA-2HDF-W8ZN-AAAA",  // too long
        "XX-7K3M-9QXA-2HDF-W8ZN",       // wrong prefix
        "7K3M-9QXA-2HDF-W8ZN",          // no prefix
        "PS-7K3M-9QXA-2HDF-W8Z",        // short group
        "PS-7K3M-9QXA-2HDF-W8ZNN",      // long group
        "PS-7K3M-9QXA-2HDF-W8Z!",       // bad character
        "PS-ILOU-9QXA-2HDF-W8ZN",       // I, L, O, U are not in Crockford base32
        "PS7K3M9QXA2HDFW8ZN",           // no dashes
        "PS-7K3M 9QXA 2HDF W8ZN",       // spaces are stripped, not turned into dashes
        "PS-7K3M_9QXA_2HDF_W8ZN"        // wrong separators
    })
    void malformedKeyIsRejectedWithoutEchoingIt(String key) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> valid().key(key).build());
        assertFalse(e.getMessage().contains(key), e.getMessage());
        assertEquals("licence key is not in the expected format PS-XXXX-XXXX-XXXX-XXXX", e.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "PS-7K3M-9QXA-2HDF-W8ZN",
        "ps-7k3m-9qxa-2hdf-w8zn",
        "  PS-7K3M-9QXA-2HDF-W8ZN  ",
        "PS-7K3M- 9QXA -2HDF-\tW8ZN",   // spaces inside (e.g. a wrapped paste) are stripped
        "\tps-7k3m-9qxa-2hdf-w8zn\n",
        "PS-0000-0000-0000-0000",
        "PS-ZZZZ-VVVV-TTTT-RRRR"
    })
    void keysAreAcceptedInAnyCaseWithSpaces(String key) {
        assertDoesNotThrow(() -> valid().key(key).build());
    }

    @Test
    void toStringMasksTheKey() {
        String text = valid().build().toString();

        assertEquals(
                "PeakstoneLicense[product=my-plugin, key=PS-7K3M-****-****-W8ZN, endpoint=https://peakstone.app/api/v1/licenses/verify]",
                text);
        assertFalse(text.contains("9QXA"));
        assertFalse(text.contains("2HDF"));
        assertFalse(text.contains(KEY));
    }

    @Test
    void maskingIsAppliedToTheNormalisedKey() {
        String text = valid().key(" ps-7k3m-9qxa-2hdf-w8zn ").build().toString();

        assertEquals(true, text.contains("key=PS-7K3M-****-****-W8ZN"), text);
    }

    // ---- public keys ------------------------------------------------------------------------

    @Test
    void publicKeyIsRequired() {
        PeakstoneLicense.Builder b = PeakstoneLicense.builder().product("p").key(KEY).dataDirectory(Path.of("d"));
        assertThrows(IllegalStateException.class, b::build);
    }

    @Test
    void dataDirectoryIsRequired() {
        PeakstoneLicense.Builder b = PeakstoneLicense.builder().product("p").key(KEY).publicKey(PUBLIC_KEY);
        assertThrows(IllegalStateException.class, b::build);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "not base64!!", "AAAA", "short", "MCowBQYDK2VwAyEA"})
    void badPublicKeysAreRejectedImmediately(String publicKey) {
        assertThrows(IllegalArgumentException.class, () -> PeakstoneLicense.builder().publicKey(publicKey));
        assertThrows(IllegalArgumentException.class, () -> PeakstoneLicense.builder().publicKey("ps-1", publicKey));
    }

    @Test
    void blankKeyIdIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> PeakstoneLicense.builder().publicKey(" ", PUBLIC_KEY));
        assertThrows(IllegalArgumentException.class, () -> PeakstoneLicense.builder().publicKey(null, PUBLIC_KEY));
    }

    // ---- other settings ---------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"peakstone.app", "ftp://peakstone.app", "https://", "https://peakstone.app/?x=1", "https://peakstone.app/#top", "file:///tmp"})
    void badBaseUrlIsRejected(String url) {
        assertThrows(IllegalArgumentException.class, () -> valid().baseUrl(URI.create(url)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://peakstone.app", "https://peakstone.app/", "https://staging.peakstone.app:8443/base/", "http://127.0.0.1:3000"})
    void baseUrlsAreAccepted(String url) {
        assertDoesNotThrow(() -> valid().baseUrl(URI.create(url)).build());
    }

    @Test
    void baseUrlTrailingSlashesDoNotDoubleUp() {
        assertEquals(
                true,
                valid().baseUrl(URI.create("https://example.test/base//")).build().toString().contains("endpoint=https://example.test/base/api/v1/licenses/verify"));
    }

    @Test
    void nonPositiveTimeoutIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> valid().timeout(Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> valid().timeout(Duration.ofSeconds(-1)));
        assertThrows(NullPointerException.class, () -> valid().timeout(null));
    }

    @Test
    void nullArgumentsFailFast() {
        assertThrows(NullPointerException.class, () -> valid().baseUrl(null));
        assertThrows(NullPointerException.class, () -> valid().dataDirectory(null));
        assertThrows(NullPointerException.class, () -> valid().publicKey(null));
    }
}
