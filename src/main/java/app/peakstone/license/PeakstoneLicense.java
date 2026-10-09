package app.peakstone.license;

import app.peakstone.license.LicenseResult.Invalid;
import app.peakstone.license.LicenseResult.Offline;
import app.peakstone.license.LicenseResult.Status;
import app.peakstone.license.LicenseResult.Unavailable;
import app.peakstone.license.LicenseResult.Valid;
import java.io.IOException;
import java.lang.System.Logger.Level;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Checks a Peakstone licence key for one plugin.
 *
 * <pre>{@code
 * PeakstoneLicense license = PeakstoneLicense.builder()
 *         .product("my-plugin-slug")
 *         .key(config.getString("license-key"))
 *         .publicKey("ps-1", PEAKSTONE_PUBLIC_KEY)
 *         .dataDirectory(getDataFolder().toPath())
 *         .build();
 *
 * LicenseResult result = license.verify();
 * }</pre>
 *
 * <p>Peakstone answers are signed with Ed25519 and bound to this plugin, licence key, server
 * instance and a per-request nonce, so they cannot be forged or replayed. A valid answer is cached
 * in the data directory and used as an offline fallback until it expires. See {@link
 * LicenseResult} for the possible outcomes.
 *
 * <p>Instances are thread-safe and cheap; the licence key is never exposed by {@link #toString()}
 * or in log messages.
 */
public final class PeakstoneLicense {
    static final String VERSION = "0.2.0";

    private static final System.Logger LOG = System.getLogger("app.peakstone.license");
    private static final URI DEFAULT_BASE_URL = URI.create("https://peakstone.app");
    private static final String VERIFY_PATH = "/api/v1/licenses/verify";
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration MAX_RETRY_AFTER = Duration.ofHours(24);
    private static final int NONCE_BYTES = 24; // 32 base64url characters
    private static final int MAX_RESPONSE_BYTES = 64 * 1024;
    private static final Pattern PRODUCT = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");
    /** What Peakstone accepts as a plugin version (the same shape as release versions). */
    private static final Pattern PLUGIN_VERSION = Pattern.compile("[0-9A-Za-z][0-9A-Za-z.\\-+_]{0,31}");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final String product;
    private final String key; // normalised; never printed
    private final String maskedKey;
    private final String pluginVersion; // null = not reported
    private final TrustedKeys trustedKeys;
    private final Store store;
    private final URI endpoint;
    private final Duration timeout;
    private final String userAgent;
    private final Clock clock;

    private String instance; // guarded by this

    private PeakstoneLicense(Builder b, String key) {
        this.product = b.product.strip();
        this.key = key;
        this.maskedKey = LicenseKeys.mask(key);
        this.pluginVersion = acceptablePluginVersion(b.pluginVersion, maskedKey);
        this.trustedKeys = new TrustedKeys(b.keys);
        this.store = new Store(b.dataDirectory);
        this.endpoint = URI.create(b.baseUrl.toString().replaceAll("/+$", "") + VERIFY_PATH);
        this.timeout = b.timeout;
        this.userAgent = "peakstone-license-java/" + VERSION + " (" + product + ")";
        this.clock = b.clock;
    }

    /** Starts configuring a licence check. */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Checks the licence now. This blocks for up to the configured timeout, so do not call it on
     * the server's main thread; use {@link #verifyAsync()}.
     *
     * <p>Network problems never throw: they produce {@link Offline} (if a usable cached answer
     * exists) or {@link Unavailable}.
     */
    public LicenseResult verify() {
        String instanceId = instanceId();
        String nonce = newNonce();

        HttpResponse<byte[]> response;
        try {
            response = post(instanceId, nonce);
        } catch (IOException e) {
            return couldNotVerify("network error: " + describe(e), Duration.ZERO, instanceId);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Unavailable("interrupted", Duration.ZERO);
        }

        if (response.statusCode() != 200) {
            return couldNotVerify(
                    "Peakstone answered HTTP " + response.statusCode(), retryAfter(response), instanceId);
        }
        return handleLive(response.body(), instanceId, nonce);
    }

    /**
     * Like {@link #verify()}, but runs on a virtual thread and returns immediately. The future
     * completes normally in every operational case; it only fails on programming errors.
     */
    public CompletableFuture<LicenseResult> verifyAsync() {
        CompletableFuture<LicenseResult> future = new CompletableFuture<>();
        Thread.ofVirtual()
                .name("peakstone-license-verify")
                .start(() -> {
                    try {
                        future.complete(verify());
                    } catch (Throwable t) {
                        future.completeExceptionally(t);
                    }
                });
        return future;
    }

    /**
     * Re-checks the licence every {@code interval} (the first check happens after one interval, so
     * call {@link #verify()} yourself at startup) and passes each result to {@code onResult}.
     *
     * <p>The callback runs on a virtual thread, not on the server's main thread; use the
     * platform's scheduler to get back to it. If Peakstone answers with a longer {@code
     * retry-after} than {@code interval}, the next check waits for that long. Exceptions thrown by
     * the callback are logged and do not stop the checks.
     *
     * @return a handle; close it (for example in {@code onDisable}) to stop the checks
     */
    public PeriodicChecks startPeriodicChecks(Duration interval, Consumer<? super LicenseResult> onResult) {
        Objects.requireNonNull(interval, "interval");
        Objects.requireNonNull(onResult, "onResult");
        if (interval.isZero() || interval.isNegative()) {
            throw new IllegalArgumentException("interval must be positive");
        }
        Thread thread = Thread.ofVirtual()
                .name("peakstone-license-checks")
                .unstarted(() -> runChecks(interval, onResult));
        thread.start();
        return thread::interrupt;
    }

    /** A running series of periodic checks. {@link #close()} stops it and does not throw. */
    public interface PeriodicChecks extends AutoCloseable {
        /** Stops the checks. Safe to call more than once. */
        @Override
        void close();
    }

    @Override
    public String toString() {
        return "PeakstoneLicense[product=" + product + ", key=" + maskedKey + ", endpoint=" + endpoint + "]";
    }

    // ------------------------------------------------------------------------------------------

    private void runChecks(Duration interval, Consumer<? super LicenseResult> onResult) {
        Duration delay = interval;
        try {
            while (true) {
                Thread.sleep(delay);
                LicenseResult result = verify();
                if (Thread.currentThread().isInterrupted()) {
                    return; // closed while the check was running
                }
                try {
                    onResult.accept(result);
                } catch (RuntimeException e) {
                    LOG.log(Level.WARNING, "[" + maskedKey + "] periodic check callback failed", e);
                }
                delay = result instanceof Unavailable u && u.retryAfter().compareTo(interval) > 0
                        ? u.retryAfter()
                        : interval;
            }
        } catch (InterruptedException closed) {
            // close() was called
        }
    }

    private HttpResponse<byte[]> post(String instanceId, String nonce) throws IOException, InterruptedException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("key", key);
        body.put("product", product);
        body.put("instance", instanceId);
        body.put("nonce", nonce);
        // Which plugin build and SDK asked: Peakstone refuses pulled releases and shows authors what servers run.
        if (pluginVersion != null) {
            body.put("version", pluginVersion);
        }
        body.put("sdk", "java/" + VERSION);

        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(timeout)
                .header("content-type", "application/json")
                .header("user-agent", userAgent)
                .POST(HttpRequest.BodyPublishers.ofString(Json.object(body), StandardCharsets.UTF_8))
                .build();

        try (HttpClient client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build()) {
            return client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        }
    }

    /** Handles a 200 answer: authenticate it, then cache it, forget the cache, or reject it. */
    private LicenseResult handleLive(byte[] body, String instanceId, String nonce) {
        try {
            if (body.length > MAX_RESPONSE_BYTES) {
                throw Wire.malformed("response is too large");
            }
            Wire.Signed signed = Wire.Signed.parse(new String(body, StandardCharsets.UTF_8));
            Wire.Payload payload = verifier(instanceId).authenticate(signed, nonce);

            if (payload.valid()) {
                try {
                    store.saveCache(signed);
                } catch (IOException e) {
                    LOG.log(Level.WARNING, "[{0}] could not save the licence cache: {1}", maskedKey, describe(e));
                }
                return new Valid(
                        payload.plan(),
                        Instant.ofEpochSecond(payload.expiresAt()),
                        payload.periodEnd() == null ? null : Instant.ofEpochSecond(payload.periodEnd()));
            }

            if ("limit_reached".equals(payload.status())) {
                LOG.log(
                        Level.WARNING,
                        "[{0}] licence server limit reached: {1}",
                        maskedKey,
                        reasonOf(payload));
            }

            // A signed "no" is final: never fall back to the cache after it.
            try {
                store.deleteCache();
            } catch (IOException e) {
                LOG.log(Level.WARNING, "[{0}] could not delete the licence cache: {1}", maskedKey, describe(e));
            }
            return new Invalid(statusOf(payload.status()), reasonOf(payload));
        } catch (Rejection r) {
            LOG.log(Level.DEBUG, "[{0}] response rejected ({1}): {2}", maskedKey, r.status(), r.getMessage());
            return r.toResult();
        }
    }

    /** Falls back to the cached confirmation if there is a usable one, otherwise reports "unavailable". */
    private LicenseResult couldNotVerify(String reason, Duration retryAfter, String instanceId) {
        Optional<Offline> cached = offlineFromCache(instanceId);
        if (cached.isPresent()) {
            LOG.log(
                    Level.INFO,
                    "[{0}] {1}; using the cached licence confirmation valid until {2}",
                    maskedKey,
                    reason,
                    cached.get().expiresAt());
            return cached.get();
        }
        return new Unavailable(reason, retryAfter);
    }

    private Optional<Offline> offlineFromCache(String instanceId) {
        Optional<Wire.Signed> cached = store.loadCache();
        if (cached.isEmpty()) {
            return Optional.empty();
        }
        try {
            Wire.Payload payload = verifier(instanceId).authenticate(cached.get(), null);
            if (!payload.valid()) {
                return Optional.empty();
            }
            return Optional.of(new Offline(payload.plan(), Instant.ofEpochSecond(payload.expiresAt())));
        } catch (Rejection r) {
            LOG.log(Level.DEBUG, "[{0}] ignoring cached response: {1}", maskedKey, r.getMessage());
            return Optional.empty();
        }
    }

    private Verifier verifier(String instanceId) {
        return new Verifier(product, key, instanceId, trustedKeys, clock);
    }

    /** The server instance id, loaded or created once. Falls back to a temporary one if the disk is unusable. */
    private synchronized String instanceId() {
        if (instance == null) {
            try {
                instance = store.instanceId();
            } catch (IOException e) {
                LOG.log(
                        Level.WARNING,
                        "[{0}] could not read or write the instance id, using a temporary one: {1}",
                        maskedKey,
                        describe(e));
                instance = UUID.randomUUID().toString();
            }
        }
        return instance;
    }

    private static String newNonce() {
        byte[] bytes = new byte[NONCE_BYTES];
        RANDOM.nextBytes(bytes);
        return Wire.encodeBase64Url(bytes);
    }

    private static Status statusOf(String status) {
        if (status == null) {
            return Status.UNKNOWN;
        }
        return switch (status) {
            case "inactive" -> Status.INACTIVE;
            case "revoked" -> Status.REVOKED;
            case "wrong_product" -> Status.WRONG_PRODUCT;
            case "limit_reached" -> Status.LIMIT_REACHED;
            default -> Status.UNKNOWN;
        };
    }

    /** The version to report, or null (with a warning) if it is not in a form Peakstone accepts. */
    private static String acceptablePluginVersion(String version, String maskedKey) {
        if (version == null || version.isBlank()) {
            return null;
        }
        String v = version.strip();
        if (!PLUGIN_VERSION.matcher(v).matches()) {
            LOG.log(
                    Level.WARNING,
                    "[{0}] plugin version \"{1}\" is not reported to Peakstone: use letters, digits and . - + _ (at most 32 characters)",
                    maskedKey,
                    v.length() > 40 ? v.substring(0, 40) : v);
            return null;
        }
        return v;
    }

    private static String reasonOf(Wire.Payload payload) {
        if (payload.reason() != null && !payload.reason().isBlank()) {
            return payload.reason();
        }
        return "Licence is not valid (" + (payload.status() == null ? "unknown" : payload.status()) + ")";
    }

    /** The {@code retry-after} header in seconds; HTTP-date values are ignored. */
    private static Duration retryAfter(HttpResponse<?> response) {
        return response.headers()
                .firstValue("retry-after")
                .map(String::strip)
                .map(PeakstoneLicense::parseSeconds)
                .orElse(Duration.ZERO);
    }

    private static Duration parseSeconds(String text) {
        try {
            long seconds = Long.parseLong(text);
            if (seconds <= 0) {
                return Duration.ZERO;
            }
            Duration d = Duration.ofSeconds(seconds);
            return d.compareTo(MAX_RETRY_AFTER) > 0 ? MAX_RETRY_AFTER : d;
        } catch (NumberFormatException e) {
            return Duration.ZERO;
        }
    }

    private static String describe(Exception e) {
        String message = e.getMessage();
        return e.getClass().getSimpleName() + (message == null || message.isBlank() ? "" : ": " + message);
    }

    // ------------------------------------------------------------------------------------------

    /** Collects the settings for a {@link PeakstoneLicense}. */
    public static final class Builder {
        private String product;
        private String key;
        private final List<TrustedKeys.Entry> keys = new ArrayList<>();
        private Path dataDirectory;
        private URI baseUrl = DEFAULT_BASE_URL;
        private Duration timeout = DEFAULT_TIMEOUT;
        private String pluginVersion;
        private Clock clock = Clock.systemUTC();

        private Builder() {}

        /** The plugin's Peakstone slug, for example {@code "my-plugin-slug"}. Required. */
        public Builder product(String product) {
            this.product = product;
            return this;
        }

        /**
         * The server owner's licence key, for example {@code PS-7K3M-9QXA-2HDF-W8ZN}. Lowercase and
         * surrounding spaces are accepted. Required; it is validated by {@link #build()}.
         */
        public Builder key(String key) {
            this.key = key;
            return this;
        }

        /**
         * Trusts this Ed25519 public key for responses with any {@code keyId}. Give the raw 32-byte
         * key as base64 or base64url (the X.509 form is accepted too).
         *
         * @throws IllegalArgumentException if the key cannot be parsed
         */
        public Builder publicKey(String base64) {
            keys.add(new TrustedKeys.Entry(null, TrustedKeys.parse(Objects.requireNonNull(base64, "base64"))));
            return this;
        }

        /**
         * Trusts this Ed25519 public key for responses with the given {@code keyId}. Call it once
         * per key to support key rotation.
         *
         * @throws IllegalArgumentException if the id is blank or the key cannot be parsed
         */
        public Builder publicKey(String keyId, String base64) {
            if (keyId == null || keyId.isBlank()) {
                throw new IllegalArgumentException("keyId must not be blank");
            }
            keys.add(new TrustedKeys.Entry(keyId, TrustedKeys.parse(Objects.requireNonNull(base64, "base64"))));
            return this;
        }

        /** Where the instance id and the offline cache are stored; created if missing. Required. */
        public Builder dataDirectory(Path dataDirectory) {
            this.dataDirectory = Objects.requireNonNull(dataDirectory, "dataDirectory");
            return this;
        }

        /** Peakstone's address. Defaults to {@code https://peakstone.app}. */
        public Builder baseUrl(URI baseUrl) {
            Objects.requireNonNull(baseUrl, "baseUrl");
            String scheme = baseUrl.getScheme();
            if (scheme == null
                    || !(scheme.equalsIgnoreCase("https") || scheme.equalsIgnoreCase("http"))
                    || baseUrl.getHost() == null
                    || baseUrl.getQuery() != null
                    || baseUrl.getFragment() != null) {
                throw new IllegalArgumentException("baseUrl must be an http(s) URL without query or fragment");
            }
            this.baseUrl = baseUrl;
            return this;
        }

        /** Connect and request timeout. Defaults to 10 seconds. */
        public Builder timeout(Duration timeout) {
            Objects.requireNonNull(timeout, "timeout");
            if (timeout.isZero() || timeout.isNegative()) {
                throw new IllegalArgumentException("timeout must be positive");
            }
            this.timeout = timeout;
            return this;
        }

        /**
         * The version of your plugin, read from its descriptor at runtime (Paper: {@code
         * getPluginMeta().getVersion()}, Bukkit: {@code getDescription().getVersion()}). Sent with
         * every check so Peakstone can refuse a pulled release and show you which version each
         * server runs. Optional; a value Peakstone would not accept (letters, digits and {@code .-+_},
         * at most 32 characters) is not sent and a warning is logged.
         */
        public Builder pluginVersion(String pluginVersion) {
            this.pluginVersion = pluginVersion;
            return this;
        }

        /** For tests: replaces the system clock. */
        Builder clock(Clock clock) {
            this.clock = Objects.requireNonNull(clock, "clock");
            return this;
        }

        /**
         * Validates the settings and creates the checker. No network or disk access happens here.
         *
         * @throws IllegalArgumentException if the product or licence key is missing or malformed
         *                                  (the message never contains the key)
         * @throws IllegalStateException    if no public key or data directory was configured
         */
        public PeakstoneLicense build() {
            if (product == null || product.isBlank()) {
                throw new IllegalArgumentException("product must not be blank");
            }
            if (!PRODUCT.matcher(product.strip()).matches()) {
                throw new IllegalArgumentException(
                        "product must be the plugin slug: letters, digits, '.', '_' or '-' (max 64 characters)");
            }
            String normalized = LicenseKeys.normalize(key);
            if (normalized.isEmpty()) {
                throw new IllegalArgumentException("licence key is missing");
            }
            if (!LicenseKeys.isValid(normalized)) {
                throw new IllegalArgumentException("licence key is not in the expected format PS-XXXX-XXXX-XXXX-XXXX");
            }
            if (keys.isEmpty()) {
                throw new IllegalStateException("a public key is required: call publicKey(...)");
            }
            if (dataDirectory == null) {
                throw new IllegalStateException("dataDirectory is required");
            }
            return new PeakstoneLicense(this, normalized);
        }
    }
}
