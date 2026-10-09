package app.peakstone.license;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.peakstone.license.FakePeakstone.Reply;
import app.peakstone.license.LicenseResult.Invalid;
import app.peakstone.license.LicenseResult.Offline;
import app.peakstone.license.LicenseResult.Status;
import app.peakstone.license.LicenseResult.Unavailable;
import app.peakstone.license.LicenseResult.Valid;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class PeakstoneLicenseTest extends ServerTestBase {
    private static final long DAY = 86_400L;

    // ---- live, valid ------------------------------------------------------------------------

    @Test
    void validLiveResponse() throws IOException {
        LicenseResult result = license().verify();

        Valid valid = as(Valid.class, result);
        assertEquals("Network", valid.plan());
        assertEquals(START.plusSeconds(3 * DAY), valid.expiresAt());
        assertEquals(START.plusSeconds(30 * DAY), valid.periodEnd());
        assertTrue(result.allowsUse());

        // the response was cached exactly as received
        Wire.Signed cached = parseCache();
        assertEquals("ps-1", cached.keyId());
        assertEquals(1, fake.requests.size());
    }

    @Test
    void requestFollowsTheWireProtocol() throws IOException {
        license().verify();

        FakePeakstone.Received request = fake.requests.getFirst();
        assertEquals("POST", request.method());
        assertEquals("/api/v1/licenses/verify", request.path());
        assertEquals("application/json", request.header("content-type"));
        assertEquals("peakstone-license-java/0.2.0 (my-plugin)", request.header("user-agent"));

        Map<String, Object> body = request.json();
        assertEquals(Set.of("key", "product", "instance", "nonce", "sdk"), body.keySet());
        assertEquals("java/" + PeakstoneLicense.VERSION, body.get("sdk"));
        assertEquals(KEY, body.get("key"));
        assertEquals(PRODUCT, body.get("product"));
        assertEquals(Files.readString(instanceFile()).strip(), body.get("instance"));
        assertTrue(((String) body.get("nonce")).matches("[A-Za-z0-9_-]{22,}"), "nonce: " + body.get("nonce"));
    }

    @Test
    void theConfiguredPluginVersionIsSent() {
        builder().pluginVersion("2.4.1-beta+7").build().verify();

        assertEquals("2.4.1-beta+7", fake.requests.getFirst().json().get("version"));
    }

    @Test
    void aPluginVersionPeakstoneWouldRefuseIsNotSent() {
        for (String bad : new String[] {"", "  ", "has space", "-1.0", "x".repeat(33), "1.0\"}"}) {
            fake.requests.clear();
            builder().pluginVersion(bad).build().verify();
            assertFalse(fake.requests.getFirst().json().containsKey("version"), "sent: " + bad);
        }
    }

    @Test
    void limitReachedIsAFinalAnswerThatNeverUsesTheCache() {
        PeakstoneLicense license = license();
        as(Valid.class, license.verify());

        fake.respond(p -> {
            p.put("valid", false);
            p.put("status", "limit_reached");
            p.put("reason", "This licence is already in use on 2 servers.");
            return p;
        });
        Invalid invalid = as(Invalid.class, license.verify());
        assertEquals(Status.LIMIT_REACHED, invalid.status());
        assertFalse(invalid.allowsUse());

        // the cache is gone: an outage afterwards cannot bring the licence back
        assertFalse(Files.exists(cacheFile()));
        fake.respondRaw(r -> FakePeakstone.Reply.status(503));
        assertFalse(license.verify().allowsUse());
    }

    @Test
    void everyRequestUsesAFreshNonceAndTheSameInstance() {
        PeakstoneLicense license = license();
        license.verify();
        license.verify();

        Map<String, Object> first = fake.requests.get(0).json();
        Map<String, Object> second = fake.requests.get(1).json();
        assertNotEquals(first.get("nonce"), second.get("nonce"));
        assertEquals(first.get("instance"), second.get("instance"));
    }

    @Test
    void keyIsNormalisedBeforeItIsSent() {
        LicenseResult result = builder().key("  ps-7k3m- 9qxa-2hdf-w8zn\t").build().verify();

        as(Valid.class, result);
        assertEquals(KEY, fake.requests.getFirst().json().get("key"));
    }

    @Test
    void licenceKeyInTheResponseIsComparedAfterNormalising() {
        fake.respond(p -> {
            p.put("license", " ps-7k3m-9qxa-2hdf-w8zn ");
            return p;
        });

        as(Valid.class, license().verify());
    }

    @Test
    void planAndPeriodEndMayBeNull() {
        fake.respond(p -> {
            p.put("plan", null);
            p.put("periodEnd", null);
            return p;
        });

        Valid valid = as(Valid.class, license().verify());
        assertNull(valid.plan());
        assertNull(valid.periodEnd());
    }

    @Test
    void unknownExtraFieldsAreIgnored() {
        fake.respond(p -> {
            p.put("somethingNew", "later");
            return p;
        });

        as(Valid.class, license().verify());
    }

    // ---- live, invalid ----------------------------------------------------------------------

    @ParameterizedTest
    @CsvSource({
        "inactive,INACTIVE",
        "revoked,REVOKED",
        "wrong_product,WRONG_PRODUCT",
        "limit_reached,LIMIT_REACHED",
        "unknown,UNKNOWN",
        "some_future_status,UNKNOWN"
    })
    void signedNoIsReportedAndDeletesTheCache(String serverStatus, Status expected) {
        PeakstoneLicense license = license();
        as(Valid.class, license.verify());
        assertTrue(Files.exists(cacheFile()));

        fake.respond(p -> {
            p.put("valid", false);
            p.put("status", serverStatus);
            p.put("reason", "Subscription is not active.");
            p.put("plan", null);
            p.put("periodEnd", null);
            return p;
        });
        Invalid invalid = as(Invalid.class, license.verify());

        assertEquals(expected, invalid.status());
        assertEquals("Subscription is not active.", invalid.reason());
        assertFalse(invalid.allowsUse());
        assertFalse(Files.exists(cacheFile()), "a definitive no must delete the cache");

        // and it stays a no: with the server gone there is nothing to fall back to
        fake.close();
        as(Unavailable.class, license.verify());
    }

    @Test
    void invalidWithoutReasonGetsADefaultOne() {
        fake.respond(p -> {
            p.put("valid", false);
            p.put("status", "inactive");
            p.remove("expiresAt");
            return p;
        });

        Invalid invalid = as(Invalid.class, license().verify());
        assertEquals(Status.INACTIVE, invalid.status());
        assertTrue(invalid.reason().contains("inactive"), invalid.reason());
    }

    @Test
    void invalidResponseMustAlsoMatchTheRequestNonce() {
        PeakstoneLicense license = license();
        as(Valid.class, license.verify());

        // an attacker replaying an old signed "no" must not be able to switch the plugin off
        fake.respond(p -> {
            p.put("valid", false);
            p.put("status", "inactive");
            p.put("nonce", "an-old-nonce-from-another-request");
            return p;
        });

        assertEquals(Status.BAD_SIGNATURE, as(Invalid.class, license.verify()).status());
        assertTrue(Files.exists(cacheFile()), "an untrusted response must not delete the cache");
    }

    // ---- signatures -------------------------------------------------------------------------

    @Test
    void tamperedPayloadIsABadSignatureAndKeepsTheCache() throws IOException {
        PeakstoneLicense license = license();
        as(Valid.class, license.verify());
        String cacheBefore = Files.readString(cacheFile());

        fake.respondRaw(r -> {
            Map<String, Object> payload = fake.payloadFor(r);
            byte[] signedBytes = Json.object(payload).getBytes(StandardCharsets.UTF_8);
            payload.put("plan", "Enterprise"); // changed after signing
            byte[] forged = Json.object(payload).getBytes(StandardCharsets.UTF_8);
            return fake.envelope(forged, fake.sign(signedBytes), FakePeakstone.KEY_ID);
        });

        Invalid invalid = as(Invalid.class, license.verify());
        assertEquals(Status.BAD_SIGNATURE, invalid.status());
        assertEquals(cacheBefore, Files.readString(cacheFile()));
    }

    @Test
    void anAttackerCannotTurnASignedNoIntoAYes() {
        fake.respondRaw(r -> {
            Map<String, Object> no = fake.payloadFor(r);
            no.put("valid", false);
            no.put("status", "inactive");
            byte[] signature = fake.sign(Json.object(no).getBytes(StandardCharsets.UTF_8));
            byte[] yes = Json.object(fake.payloadFor(r)).getBytes(StandardCharsets.UTF_8);
            return fake.envelope(yes, signature, FakePeakstone.KEY_ID);
        });

        assertEquals(Status.BAD_SIGNATURE, as(Invalid.class, license().verify()).status());
        assertFalse(Files.exists(cacheFile()));
    }

    @Test
    void flippedSignatureBitIsABadSignature() {
        fake.respondRaw(r -> {
            byte[] payload = Json.object(fake.payloadFor(r)).getBytes(StandardCharsets.UTF_8);
            byte[] signature = fake.sign(payload);
            signature[10] ^= 0x01;
            return fake.envelope(payload, signature, FakePeakstone.KEY_ID);
        });

        assertEquals(Status.BAD_SIGNATURE, as(Invalid.class, license().verify()).status());
    }

    @Test
    void signatureOfTheWrongLengthIsABadSignature() {
        fake.respondRaw(r -> {
            byte[] payload = Json.object(fake.payloadFor(r)).getBytes(StandardCharsets.UTF_8);
            return fake.envelope(payload, new byte[10], FakePeakstone.KEY_ID);
        });

        assertEquals(Status.BAD_SIGNATURE, as(Invalid.class, license().verify()).status());
    }

    @Test
    void signedByAnUntrustedKeyIsABadSignature() {
        KeyPair stranger = FakePeakstone.newKeyPair();
        fake.respondRaw(r -> fake.sign(stranger, FakePeakstone.KEY_ID, fake.payloadFor(r)));

        assertEquals(Status.BAD_SIGNATURE, as(Invalid.class, license().verify()).status());
    }

    // ---- the response must belong to this request -------------------------------------------

    static Stream<Arguments> rejectedPayloads() {
        long now = START.getEpochSecond();
        return Stream.of(
                Arguments.of("other product", edit(p -> p.put("product", "other-plugin")), Status.WRONG_PRODUCT, "product"),
                Arguments.of("other licence", edit(p -> p.put("license", "PS-AAAA-AAAA-AAAA-AAAA")), Status.BAD_SIGNATURE, "licence"),
                Arguments.of("other instance", edit(p -> p.put("instance", "00000000-0000-4000-8000-000000000000")), Status.BAD_SIGNATURE, "instance"),
                Arguments.of("other nonce", edit(p -> p.put("nonce", "AAAAAAAAAAAAAAAAAAAAAA")), Status.BAD_SIGNATURE, "nonce"),
                Arguments.of("missing nonce", edit(p -> p.remove("nonce")), Status.BAD_SIGNATURE, "nonce"),
                Arguments.of("expired", edit(p -> p.put("expiresAt", now - 1)), Status.BAD_SIGNATURE, "expired"),
                Arguments.of("expires right now", edit(p -> p.put("expiresAt", now)), Status.BAD_SIGNATURE, "expired"),
                Arguments.of("issued in the future", edit(p -> p.put("issuedAt", now + 301)), Status.BAD_SIGNATURE, "future"),
                Arguments.of("unsupported version", edit(p -> p.put("v", 2L)), Status.MALFORMED, "version"),
                Arguments.of("valid without expiry", edit(p -> p.remove("expiresAt")), Status.MALFORMED, "expiresAt"),
                Arguments.of("valid without issue time", edit(p -> p.remove("issuedAt")), Status.MALFORMED, "issuedAt"),
                Arguments.of("no valid flag", edit(p -> p.remove("valid")), Status.MALFORMED, "valid"),
                Arguments.of("valid is a string", edit(p -> p.put("valid", "true")), Status.MALFORMED, "valid"),
                Arguments.of("version is a string", edit(p -> p.put("v", "1")), Status.MALFORMED, "\"v\""),
                Arguments.of("no product", edit(p -> p.remove("product")), Status.MALFORMED, "product"),
                Arguments.of("timestamp as string", edit(p -> p.put("expiresAt", "tomorrow")), Status.MALFORMED, "expiresAt"),
                Arguments.of("negative timestamp", edit(p -> p.put("issuedAt", -5L)), Status.MALFORMED, "issuedAt"));
    }

    private static UnaryOperator<Map<String, Object>> edit(Consumer<Map<String, Object>> edit) {
        return p -> {
            edit.accept(p);
            return p;
        };
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rejectedPayloads")
    void payloadThatDoesNotFitIsRejected(String name, UnaryOperator<Map<String, Object>> tweak, Status status, String fragment) {
        fake.respond(tweak);

        Invalid invalid = as(Invalid.class, license().verify());

        assertEquals(status, invalid.status(), invalid.reason());
        assertTrue(invalid.reason().contains(fragment), invalid.reason());
        assertFalse(invalid.allowsUse());
        assertFalse(Files.exists(cacheFile()));
    }

    @Test
    void issuedAtWithinFiveMinutesOfSkewIsAccepted() {
        fake.respond(edit(p -> p.put("issuedAt", START.getEpochSecond() + 299)));

        as(Valid.class, license().verify());
    }

    @Test
    void aValidResponseCannotBeReplayedForALaterRequest() {
        PeakstoneLicense license = license();
        as(Valid.class, license.verify());
        Reply captured = fake.lastReply;

        fake.respondRaw(r -> captured);
        Invalid invalid = as(Invalid.class, license.verify());

        assertEquals(Status.BAD_SIGNATURE, invalid.status());
        assertTrue(invalid.reason().contains("nonce"), invalid.reason());
    }

    // ---- malformed --------------------------------------------------------------------------

    static Stream<String> malformedBodies() {
        return Stream.of(
                "",
                "not json",
                "<html><body>Please log in to the hotel wifi</body></html>",
                "[]",
                "{}",
                "{\"payload\":\"abc\"}",
                "{\"signature\":\"abc\"}",
                "{\"payload\":1,\"signature\":\"x\"}",
                "{\"payload\":\"x\",\"signature\":\"y\",\"keyId\":5}",
                "{\"payload\":\"!!!\",\"signature\":\"!!!\"}",
                "{\"payload\":\"x\",\"signature\":\"y\"} trailing",
                "{\"payload\":\"" + "A".repeat(70_000) + "\",\"signature\":\"x\"}");
    }

    @ParameterizedTest
    @MethodSource("malformedBodies")
    void malformedResponseBody(String body) {
        fake.respondRaw(r -> Reply.ok(body));

        Invalid invalid = as(Invalid.class, license().verify());

        assertEquals(Status.MALFORMED, invalid.status(), invalid.reason());
        assertFalse(invalid.allowsUse());
    }

    /** The valid default answer with an unknown extra field that makes it {@code extraBytes} bigger. */
    private Reply paddedValidReply(FakePeakstone.Received request, int extraBytes) {
        String body = fake.valid(request).body();
        return Reply.ok(body.substring(0, body.length() - 1) + ",\"pad\":\"" + "A".repeat(extraBytes) + "\"}");
    }

    @Test
    void oversizedResponseIsRejectedEvenIfItIsOtherwiseValid() {
        fake.respondRaw(r -> paddedValidReply(r, 70_000));

        Invalid invalid = as(Invalid.class, license().verify());

        assertEquals(Status.MALFORMED, invalid.status());
        assertTrue(invalid.reason().contains("too large"), invalid.reason());
        assertFalse(Files.exists(cacheFile()));
    }

    @Test
    void largeButReasonableResponseIsAccepted() {
        fake.respondRaw(r -> paddedValidReply(r, 60_000));

        as(Valid.class, license().verify());
    }

    static Stream<String> malformedSignedPayloads() {
        return Stream.of(
                "not json",
                "{}",
                "{\"v\":1}",
                "[1,2,3]",
                "{\"v\":1.5,\"valid\":true,\"product\":\"p\",\"license\":\"l\",\"instance\":\"i\"}",
                "{\"v\":1,\"valid\":true,\"product\":\"p\",\"license\":\"l\",\"instance\":\"i\",\"issuedAt\":1.5}",
                "{\"v\":1,\"valid\":true,\"product\":\"p\",\"license\":\"l\",\"instance\":\"i\",\"issuedAt\":99999999999999}",
                "{\"v\":1,\"valid\":true,\"valid\":false,\"product\":\"p\",\"license\":\"l\",\"instance\":\"i\"}");
    }

    @ParameterizedTest
    @MethodSource("malformedSignedPayloads")
    void malformedButCorrectlySignedPayload(String payload) {
        fake.respondRaw(r -> fake.signRaw(fake.keyPair, FakePeakstone.KEY_ID, payload.getBytes(StandardCharsets.UTF_8)));

        assertEquals(Status.MALFORMED, as(Invalid.class, license().verify()).status());
    }

    @Test
    void payloadThatIsNotUtf8IsMalformed() {
        byte[] bad = {'{', '"', 'v', '"', ':', '1', ',', '"', 'x', '"', ':', '"', (byte) 0xff, '"', '}'};
        fake.respondRaw(r -> fake.signRaw(fake.keyPair, FakePeakstone.KEY_ID, bad));

        assertEquals(Status.MALFORMED, as(Invalid.class, license().verify()).status());
    }

    // ---- offline grace ----------------------------------------------------------------------

    @Test
    void serverDownAfterAValidCheckGivesOfflineFromTheCache() {
        PeakstoneLicense license = license();
        Valid live = as(Valid.class, license.verify());

        fake.close();
        LicenseResult result = license.verify();

        Offline offline = as(Offline.class, result);
        assertEquals(live.plan(), offline.plan());
        assertEquals(live.expiresAt(), offline.expiresAt());
        assertTrue(result.allowsUse());
    }

    @Test
    void cacheSurvivesARestart() {
        as(Valid.class, license().verify());
        fake.close();

        // a brand new object over the same data directory, as after a server restart
        Offline offline = as(Offline.class, license().verify());

        assertEquals("Network", offline.plan());
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 404, 429, 500, 502, 503})
    void anyFailedAnswerFallsBackToTheCache(int status) {
        PeakstoneLicense license = license();
        as(Valid.class, license.verify());

        fake.respondRaw(r -> Reply.status(status));

        as(Offline.class, license.verify());
    }

    @Test
    void offlineGraceEndsWhenTheCachedResponseExpires() {
        PeakstoneLicense license = license();
        as(Valid.class, license.verify());
        fake.close();

        clock.advance(Duration.ofDays(3).minusSeconds(1));
        as(Offline.class, license.verify());

        clock.advance(Duration.ofSeconds(1));
        Unavailable unavailable = as(Unavailable.class, license.verify());
        assertFalse(unavailable.allowsUse());
        assertTrue(unavailable.reason().contains("network error"), unavailable.reason());
    }

    @Test
    void nothingCachedAndServerDownIsUnavailable() {
        fake.close();

        Unavailable unavailable = as(Unavailable.class, license().verify());

        assertEquals(Duration.ZERO, unavailable.retryAfter());
        assertFalse(Files.exists(cacheFile()));
    }

    @Test
    void cachedResponseIsRejectedWhenTheClockWasWoundBack() {
        PeakstoneLicense license = license();
        as(Valid.class, license.verify());
        fake.close();

        clock.set(START.minus(Duration.ofMinutes(10)));

        as(Unavailable.class, license.verify());
    }

    @Test
    void tamperedCacheIsIgnored() throws IOException {
        as(Valid.class, license().verify());
        fake.close();

        Wire.Signed cached = parseCache();
        byte[] payload = Wire.decodeBase64(cached.payload());
        String forgedPayload = new String(payload, StandardCharsets.UTF_8).replace("Network", "Network2");
        Wire.Signed forged = new Wire.Signed(
                Wire.encodeBase64Url(forgedPayload.getBytes(StandardCharsets.UTF_8)), cached.signature(), cached.keyId());
        Files.writeString(cacheFile(), forged.toJson());

        as(Unavailable.class, license().verify());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "garbage", "[]", "{\"payload\":\"x\"}"})
    void corruptCacheIsIgnored(String content) throws IOException {
        fake.close();
        Files.writeString(cacheFile(), content);

        as(Unavailable.class, license().verify());
    }

    @Test
    void aSignedNoPlantedAsTheCacheIsNeverUsedOffline() throws IOException {
        PeakstoneLicense license = license();
        fake.respond(p -> {
            p.put("valid", false);
            p.put("status", "inactive");
            return p;
        });
        as(Invalid.class, license.verify());
        // fake.lastReply is a genuine signed "inactive" answer for exactly this install; plant it as the cache
        Files.writeString(cacheFile(), fake.lastReply.body());
        fake.close();

        as(Unavailable.class, license.verify());
    }

    @Test
    void cacheFromAnotherProductIsNotUsed() {
        as(Valid.class, license().verify());
        fake.close();

        as(Unavailable.class, builder().product("another-plugin").build().verify());
    }

    @Test
    void cacheFromAnotherLicenceKeyIsNotUsed() {
        as(Valid.class, license().verify());
        fake.close();

        as(Unavailable.class, builder().key("PS-AAAA-BBBB-CCCC-DDDD").build().verify());
    }

    @Test
    void cacheFromAnotherServerInstanceIsNotUsed() throws IOException {
        as(Valid.class, license().verify());
        fake.close();

        Files.writeString(instanceFile(), "7a3b9d1e-5c2f-4e8a-9b6d-1f0e2d3c4b5a"); // e.g. the cache was copied here

        as(Unavailable.class, license().verify());
    }

    // ---- rate limiting and server errors ----------------------------------------------------

    @Test
    void rateLimitedWithRetryAfter() {
        fake.respondRaw(r -> Reply.status(429, "retry-after", "42"));

        Unavailable unavailable = as(Unavailable.class, license().verify());

        assertEquals(Duration.ofSeconds(42), unavailable.retryAfter());
        assertTrue(unavailable.reason().contains("429"), unavailable.reason());
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "|0",
        "0|0",
        "-5|0",
        "soon|0",
        "Wed, 21 Oct 2026 07:28:00 GMT|0",
        "  7  |7",
        "999999999|86400"
    })
    void retryAfterIsParsedDefensively(String header, long expectedSeconds) {
        fake.respondRaw(r -> header == null || header.isEmpty() ? Reply.status(429) : Reply.status(429, "retry-after", header));

        Unavailable unavailable = as(Unavailable.class, license().verify());

        assertEquals(Duration.ofSeconds(expectedSeconds), unavailable.retryAfter());
    }

    @Test
    void rateLimitedWithAUsableCacheIsOffline() {
        PeakstoneLicense license = license();
        as(Valid.class, license.verify());

        fake.respondRaw(r -> Reply.status(429, "retry-after", "60"));

        as(Offline.class, license.verify());
    }

    @ParameterizedTest
    @CsvSource({"400,HTTP 400", "403,HTTP 403", "500,HTTP 500", "503,HTTP 503", "302,HTTP 302"})
    void unsignedFailuresWithoutCacheAreUnavailable(int status, String reason) {
        fake.respondRaw(r -> Reply.status(status, "location", "http://127.0.0.1:1/elsewhere"));

        Unavailable unavailable = as(Unavailable.class, license().verify());

        assertTrue(unavailable.reason().contains(reason), unavailable.reason());
        assertEquals(1, fake.requests.size(), "redirects must not be followed");
    }

    @Test
    void slowServerTimesOut() {
        fake.respondRaw(r -> {
            sleep(3_000);
            return fake.valid(r);
        });
        long start = System.nanoTime();

        Unavailable unavailable = as(Unavailable.class, builder().timeout(Duration.ofMillis(300)).build().verify());

        assertTrue(Duration.ofNanos(System.nanoTime() - start).toMillis() < 2_500);
        assertTrue(unavailable.reason().contains("HttpTimeoutException"), unavailable.reason());
    }

    // ---- key rotation -----------------------------------------------------------------------

    @Test
    void rotatedKeysAreAcceptedByTheirKeyId() {
        KeyPair next = FakePeakstone.newKeyPair();
        PeakstoneLicense license = builder().publicKey("ps-2", FakePeakstone.rawPublicKeyBase64(next)).build();

        fake.respondRaw(r -> fake.sign(next, "ps-2", fake.payloadFor(r)));
        as(Valid.class, license.verify());

        fake.respondRaw(r -> fake.sign(fake.keyPair, "ps-1", fake.payloadFor(r)));
        as(Valid.class, license.verify());
    }

    @Test
    void signatureUnderTheWrongKeyIdIsRejected() {
        KeyPair next = FakePeakstone.newKeyPair();
        PeakstoneLicense license = builder().publicKey("ps-2", FakePeakstone.rawPublicKeyBase64(next)).build();

        fake.respondRaw(r -> fake.sign(next, "ps-1", fake.payloadFor(r))); // ps-2's signature claiming to be ps-1

        assertEquals(Status.BAD_SIGNATURE, as(Invalid.class, license.verify()).status());
    }

    @Test
    void unknownKeyIdIsRejected() {
        fake.respondRaw(r -> fake.sign(fake.keyPair, "ps-9", fake.payloadFor(r)));

        assertEquals(Status.BAD_SIGNATURE, as(Invalid.class, license().verify()).status());
    }

    @Test
    void singleKeyWithoutAnIdAcceptsAnyKeyId() {
        PeakstoneLicense license = anonymousKeyLicense();

        for (String keyId : new String[] {"ps-1", "ps-77", "whatever", null}) {
            fake.respondRaw(r -> fake.sign(fake.keyPair, keyId, fake.payloadFor(r)));
            as(Valid.class, license.verify());
        }
    }

    @Test
    void keyWithoutAnIdStillChecksTheSignature() {
        PeakstoneLicense license = anonymousKeyLicense();
        fake.respondRaw(r -> fake.sign(FakePeakstone.newKeyPair(), "ps-1", fake.payloadFor(r)));

        assertEquals(Status.BAD_SIGNATURE, as(Invalid.class, license.verify()).status());
    }

    @Test
    void namedKeysRequireAKeyIdInTheResponse() {
        fake.respondRaw(r -> fake.sign(fake.keyPair, null, fake.payloadFor(r)));

        assertEquals(Status.BAD_SIGNATURE, as(Invalid.class, license().verify()).status());
    }

    @Test
    void cacheSignedByARetiredKeyIsNotTrusted() {
        as(Valid.class, license().verify());
        fake.close();

        KeyPair next = FakePeakstone.newKeyPair();
        PeakstoneLicense afterRotation = PeakstoneLicense.builder()
                .product(PRODUCT)
                .key(KEY)
                .publicKey("ps-2", FakePeakstone.rawPublicKeyBase64(next))
                .dataDirectory(dir)
                .baseUrl(fake.baseUrl())
                .clock(clock)
                .build();

        as(Unavailable.class, afterRotation.verify());
    }

    // ---- async, concurrency, files ----------------------------------------------------------

    @Test
    void verifyAsyncReturnsImmediately() throws Exception {
        fake.respondRaw(r -> {
            sleep(400);
            return fake.valid(r);
        });

        CompletableFuture<LicenseResult> future = license().verifyAsync();

        assertFalse(future.isDone(), "must not block the caller");
        as(Valid.class, future.get(10, TimeUnit.SECONDS));
    }

    @Test
    void verifyAsyncReportsFailuresAsResultsNotExceptions() throws Exception {
        fake.close();

        as(Unavailable.class, license().verifyAsync().get(10, TimeUnit.SECONDS));
    }

    @Test
    void cacheWriteLeavesNoTemporaryFiles() throws IOException {
        PeakstoneLicense license = license();
        for (int i = 0; i < 5; i++) {
            as(Valid.class, license.verify());
        }

        assertEquals(List.of(Store.INSTANCE_FILE, Store.CACHE_FILE), filesInDir());
    }

    @Test
    void concurrentChecksNeverLeaveATornCacheOrTempFiles() throws Exception {
        PeakstoneLicense license = license();
        List<CompletableFuture<LicenseResult>> futures = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            futures.add(license.verifyAsync());
        }
        for (CompletableFuture<LicenseResult> future : futures) {
            as(Valid.class, future.get(20, TimeUnit.SECONDS));
        }

        assertEquals(List.of(Store.INSTANCE_FILE, Store.CACHE_FILE), filesInDir());
        fake.close();
        as(Offline.class, license.verify()); // the final cache file is complete and verifies
    }

    @Test
    void failedCacheWriteDoesNotFailTheCheckAndLeavesNoTempFiles() throws IOException {
        // make the cache path unusable: a non-empty directory cannot be replaced by a file
        Files.createDirectories(cacheFile());
        Files.writeString(cacheFile().resolve("blocker"), "x");

        as(Valid.class, license().verify());

        assertEquals(List.of(Store.INSTANCE_FILE, Store.CACHE_FILE), filesInDir());
    }

    @Test
    void dataDirectoryIsCreatedOnDemand() {
        PeakstoneLicense license = builder().dataDirectory(dir.resolve("plugins/MyPlugin/data")).build();

        as(Valid.class, license.verify());

        assertTrue(Files.isRegularFile(dir.resolve("plugins/MyPlugin/data").resolve(Store.CACHE_FILE)));
    }

    @Test
    void unusableDataDirectoryStillVerifiesOnline() throws IOException {
        Path blocker = dir.resolve("a-file");
        Files.writeString(blocker, "not a directory");
        PeakstoneLicense license = builder().dataDirectory(blocker.resolve("data")).build();

        as(Valid.class, license.verify());
        assertFalse(Files.exists(blocker.resolve("data")));

        fake.close();
        as(Unavailable.class, license.verify()); // nothing could be cached
    }

    // ---- helpers ----------------------------------------------------------------------------

    private PeakstoneLicense anonymousKeyLicense() {
        return PeakstoneLicense.builder()
                .product(PRODUCT)
                .key(KEY)
                .publicKey(fake.publicKeyBase64())
                .dataDirectory(dir)
                .baseUrl(fake.baseUrl())
                .clock(clock)
                .build();
    }

    private Wire.Signed parseCache() throws IOException {
        try {
            return Wire.Signed.parse(Files.readString(cacheFile()));
        } catch (Rejection e) {
            throw new AssertionError("cache file is not a valid response envelope", e);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
