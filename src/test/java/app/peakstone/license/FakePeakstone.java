package app.peakstone.license;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.time.Clock;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.UnaryOperator;

/**
 * A stand-in for peakstone.app: a local HTTP server that answers {@code POST
 * /api/v1/licenses/verify} with Ed25519-signed payloads, following the wire protocol. By default it
 * says "valid" for whatever it is asked about; tests change the behaviour per scenario.
 */
final class FakePeakstone implements AutoCloseable {
    static final String KEY_ID = "ps-1";

    /** One request as received by the server. */
    record Received(String method, String path, Headers headers, String body, Map<String, Object> json, long nanos) {
        String header(String name) {
            return headers.getFirst(name);
        }
    }

    /** One response to send. */
    record Reply(int status, String body, Map<String, String> headers) {
        static Reply ok(String body) {
            return new Reply(200, body, Map.of());
        }

        static Reply status(int status) {
            return new Reply(status, "", Map.of());
        }

        static Reply status(int status, String header, String value) {
            return new Reply(status, "", Map.of(header, value));
        }
    }

    final KeyPair keyPair = newKeyPair();
    final List<Received> requests = new CopyOnWriteArrayList<>();
    /** The most recent reply that was sent, for replay tests. */
    volatile Reply lastReply;

    private final Clock clock;
    private final HttpServer server;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile Function<Received, Reply> handler = this::valid;

    FakePeakstone(Clock clock) throws IOException {
        this.clock = clock;
        this.server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        server.setExecutor(executor);
        server.createContext("/", this::handle);
        server.start();
    }

    URI baseUrl() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    String publicKeyBase64() {
        return rawPublicKeyBase64(keyPair);
    }

    // ---- behaviour -------------------------------------------------------------------------

    /** Answer every request with this function. */
    void respondRaw(Function<Received, Reply> handler) {
        this.handler = handler;
    }

    /** Answer with the default valid payload after {@code tweak} changed it, signed by the fake's own key. */
    void respond(UnaryOperator<Map<String, Object>> tweak) {
        respondRaw(r -> sign(keyPair, KEY_ID, tweak.apply(payloadFor(r))));
    }

    void respondValid() {
        respondRaw(this::valid);
    }

    /** The default answer: an active "Network" subscription, valid for three days. */
    Reply valid(Received r) {
        return sign(keyPair, KEY_ID, payloadFor(r));
    }

    /** The default payload for a request: everything echoed back, valid, three days of offline grace. */
    Map<String, Object> payloadFor(Received r) {
        long now = clock.instant().getEpochSecond();
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("v", 1L);
        p.put("valid", true);
        p.put("status", "active");
        p.put("reason", null);
        p.put("product", r.json().get("product"));
        p.put("license", r.json().get("key"));
        p.put("instance", r.json().get("instance"));
        p.put("nonce", r.json().get("nonce"));
        p.put("plan", "Network");
        p.put("issuedAt", now);
        p.put("expiresAt", now + 3 * 86_400L);
        p.put("periodEnd", now + 30 * 86_400L);
        return p;
    }

    // ---- signing ---------------------------------------------------------------------------

    Reply sign(KeyPair pair, String keyId, Map<String, Object> payload) {
        return signRaw(pair, keyId, Json.object(payload).getBytes(StandardCharsets.UTF_8));
    }

    Reply signRaw(KeyPair pair, String keyId, byte[] payload) {
        return envelope(payload, sign(pair.getPrivate(), payload), keyId);
    }

    Reply envelope(byte[] payload, byte[] signature, String keyId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("payload", Wire.encodeBase64Url(payload));
        m.put("signature", Wire.encodeBase64Url(signature));
        m.put("keyId", keyId);
        return Reply.ok(Json.object(m));
    }

    byte[] sign(byte[] data) {
        return sign(keyPair.getPrivate(), data);
    }

    static byte[] sign(PrivateKey key, byte[] data) {
        try {
            Signature s = Signature.getInstance("Ed25519");
            s.initSign(key);
            s.update(data);
            return s.sign();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    static KeyPair newKeyPair() {
        try {
            return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The raw 32-byte public key, base64url without padding (the last 32 bytes of the X.509 encoding). */
    static String rawPublicKeyBase64(KeyPair pair) {
        byte[] encoded = pair.getPublic().getEncoded();
        return Wire.encodeBase64Url(Arrays.copyOfRange(encoded, encoded.length - 32, encoded.length));
    }

    // ---- server ----------------------------------------------------------------------------

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Map<String, Object> json;
            try {
                json = Json.parseObject(body);
            } catch (IllegalArgumentException e) {
                json = Map.of();
            }
            Received received = new Received(
                    exchange.getRequestMethod(),
                    exchange.getRequestURI().getPath(),
                    exchange.getRequestHeaders(),
                    body,
                    json,
                    System.nanoTime());
            requests.add(received);

            Reply reply = handler.apply(received);
            lastReply = reply;
            reply.headers().forEach((k, v) -> exchange.getResponseHeaders().add(k, v));
            byte[] out = reply.body().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(reply.status(), out.length == 0 ? -1 : out.length);
            if (out.length > 0) {
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(out);
                }
            }
        }
    }

    /** Stops the server; connecting afterwards is refused. Safe to call twice. */
    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            server.stop(0);
            executor.shutdownNow();
        }
    }
}
