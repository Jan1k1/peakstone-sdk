# Peakstone licence SDK for Java

Licence checks for Minecraft plugins sold on [Peakstone](https://peakstone.app). Add it to a Paper, Velocity
or Bukkit plugin and it asks Peakstone, on startup and periodically, whether the server owner's licence key
(`PS-7K3M-9QXA-2HDF-W8ZN`) is valid for your plugin.

- **Signed answers.** Every response from Peakstone is signed with Ed25519 and bound to your plugin, the licence
  key, the server and the individual request, so a fake server or a replayed response is rejected.
- **Offline grace.** A valid answer is cached on disk and keeps the plugin running through Peakstone or network
  outages until it expires.
- **No dependencies, about 40 KB.** Only `java.base` and `java.net.http`. Shade and relocate it into your plugin.
- **Java 21+.** Works on Paper for Minecraft 1.21.x (Java 21) and on newer versions (Java 25).

Version `0.2.0`. Coordinates: `app.peakstone:peakstone-license`, package `app.peakstone.license`.

## Contents

- [Install](#install)
- [Quick start](#quick-start)
- [Results](#results)
- [Paper plugin example](#paper-plugin-example)
- [Velocity plugin example](#velocity-plugin-example)
- [Choosing a policy](#choosing-a-policy)
- [How it works](#how-it-works)
- [Offline grace](#offline-grace)
- [Security notes](#security-notes)
- [API summary](#api-summary)
- [Why the bytecode is Java 21 but the build uses JDK 25](#why-the-bytecode-is-java-21-but-the-build-uses-jdk-25)
- [Building and testing](#building-and-testing)

## Install

### Maven

Releases are published to GitHub Packages (Maven) and attached as jars to a
[GitHub Release](https://github.com/Jan1k1/peakstone-sdk/releases) when a tag `vX.Y.Z` is pushed
(`.github/workflows/release.yml`). GitHub Packages needs a login even to read: use a GitHub token with `read:packages`
as the repository credentials (see the licensing docs on Peakstone). Or download the jar from the release and shade it
into your plugin. To build it yourself:

```sh
git clone https://github.com/Jan1k1/peakstone-sdk.git
cd peakstone-sdk
mvn install        # needs JDK 21 or newer; add -DskipTests to skip the tests
```

Then depend on it:

```xml
<dependency>
  <groupId>app.peakstone</groupId>
  <artifactId>peakstone-license</artifactId>
  <version>0.2.0</version>
</dependency>
```

### Gradle

```kotlin
repositories {
    mavenLocal()   // until the SDK is published
    mavenCentral()
}

dependencies {
    implementation("app.peakstone:peakstone-license:0.2.0")
}
```

### Shade and relocate it into your plugin

Server owners run many plugins in one JVM. Relocate the SDK into your own package so it cannot clash with another
plugin's copy, and so your plugin stays a single jar.

Maven, with `maven-shade-plugin` (replace `com.example.myplugin` with your plugin's package):

```xml
<plugin>
  <groupId>org.apache.maven.plugins</groupId>
  <artifactId>maven-shade-plugin</artifactId>
  <version>3.6.2</version>
  <executions>
    <execution>
      <phase>package</phase>
      <goals><goal>shade</goal></goals>
      <configuration>
        <createDependencyReducedPom>false</createDependencyReducedPom>
        <relocations>
          <relocation>
            <pattern>app.peakstone.license</pattern>
            <shadedPattern>com.example.myplugin.libs.peakstone</shadedPattern>
          </relocation>
        </relocations>
        <filters>
          <filter>
            <artifact>app.peakstone:peakstone-license</artifact>
            <excludes>
              <!-- the SDK's JPMS descriptor must not end up in your plugin jar -->
              <exclude>module-info.class</exclude>
            </excludes>
          </filter>
        </filters>
      </configuration>
    </execution>
  </executions>
</plugin>
```

Gradle, with the Shadow plugin:

```kotlin
plugins {
    id("com.gradleup.shadow") version "<current Shadow version>"
}

tasks.shadowJar {
    relocate("app.peakstone.license", "com.example.myplugin.libs.peakstone")
    exclude("module-info.class")
}
```

Current Shadow releases need a recent Gradle: the snippet above was tested with Shadow 9.6.1 on Gradle 9.8.0
(Shadow 9.6.1 does not load on Gradle 8.14). Use an older Shadow release if you are on an older Gradle.

After relocating, your code still imports `app.peakstone.license.*` in the source; the build rewrites the
references. Both the Maven and the Gradle configuration above were run on a Paper plugin to check that the SDK ends
up entirely under your package, that no `module-info.class` is left in the jar and that the result runs on Java 21
and Java 25.

## Quick start

```java
PeakstoneLicense license = PeakstoneLicense.builder()
        .product("my-plugin-slug")                       // your plugin's slug on Peakstone
        .key(config.getString("license-key"))            // the server owner's key
        .publicKey("ps-1", PEAKSTONE_PUBLIC_KEY)         // Peakstone's Ed25519 key, see below
        .dataDirectory(getDataFolder().toPath())         // where the cache is kept
        .build();                                        // throws IllegalArgumentException if the key is missing or malformed

LicenseResult result = license.verify();                 // blocking, up to the timeout
CompletableFuture<LicenseResult> future = license.verifyAsync();   // on a virtual thread

PeakstoneLicense.PeriodicChecks checks =
        license.startPeriodicChecks(Duration.ofHours(6), r -> { /* ... */ });
// later, e.g. in onDisable: checks.close();
```

Builder options:

| Method | Required | Notes |
| --- | --- | --- |
| `product(String)` | yes | The plugin slug: letters, digits, `.`, `_`, `-`; at most 64 characters. |
| `key(String)` | yes | `PS-` plus four groups of four Crockford base32 characters (`0-9 A-H J K M N P-T V-Z`). Lowercase and spaces are accepted. |
| `publicKey(String)` / `publicKey(String keyId, String)` | yes, at least once | Peakstone's Ed25519 public key, raw 32 bytes as base64 or base64url (the X.509 form `MCowBQYDK2VwAyEA...` is accepted too). Call it once per key to support rotation. |
| `dataDirectory(Path)` | yes | Holds `peakstone-instance` and `peakstone-license.json`. Created if missing. |
| `baseUrl(URI)` | no | Defaults to `https://peakstone.app`. |
| `pluginVersion(String)` | no, recommended | Your plugin's version from its descriptor (Paper `getPluginMeta().getVersion()`, Bukkit `getDescription().getVersion()`). Sent as `version`; letters, digits and `.-+_`, at most 32 characters, otherwise it is not sent and a warning is logged. Peakstone shows it per server and refuses a pulled release. |
| `timeout(Duration)` | no | Connect and request timeout, defaults to 10 seconds. |

`build()` does no network or disk access. The licence key is validated there and the error message never contains
it.

### Peakstone's public key

Responses are signed by Peakstone, and the SDK only trusts keys you configure. The public key is public, so it
is fine to embed it in your plugin as a constant. Peakstone identifies the key that signed each response with a
`keyId` (for example `ps-1`):

- `publicKey("ps-1", key)` trusts the key only for responses that name `ps-1`. To support a key rotation, add the
  next key as well: `.publicKey("ps-1", oldKey).publicKey("ps-2", newKey)`.
- `publicKey(key)` without an id trusts the key for any `keyId`. This is fine if you only ever configure one key.

## Results

`verify()` and `verifyAsync()` never throw for operational problems (no network, timeouts, server errors). They
return a sealed `LicenseResult`:

| Result | Meaning | `allowsUse()` |
| --- | --- | --- |
| `Valid(plan, expiresAt, periodEnd)` | Peakstone confirmed the licence just now. `expiresAt` is how long the answer may be reused offline; `periodEnd` is the end of the paid period (may be `null`). | `true` |
| `Offline(plan, expiresAt)` | Peakstone could not be reached, but a signed, unexpired confirmation from an earlier check is on disk. | `true` |
| `Invalid(status, reason)` | The licence must not be used. See the statuses below. | `false` |
| `Unavailable(reason, retryAfter)` | Peakstone could not be reached and there is no usable cache. This says nothing about the licence itself. `retryAfter` is `Duration.ZERO` unless Peakstone sent a `retry-after` header. | `false` |

`Invalid.status()` is a `LicenseResult.Status`:

| Status | Cause |
| --- | --- |
| `UNKNOWN` | Peakstone does not know this key. |
| `INACTIVE` | The subscription is not active (cancelled, expired, unpaid). |
| `REVOKED` | The licence was revoked. |
| `WRONG_PRODUCT` | The key is valid but for a different plugin. |
| `LIMIT_REACHED` | A new server, and the licence already runs on the most servers the plugin allows. Servers that already run keep working. The buyer frees a slot with "Reset servers" in their Peakstone account. |
| `BAD_SIGNATURE` | The response was not authentic for this request: wrong signature or unknown signing key, a response meant for another instance, licence or request (including replays), or outside its validity window (check the server's clock). |
| `MALFORMED` | The response could not be parsed, or has an unsupported version. Typical causes are a captive portal or a proxy that answers `200` with HTML. |

The first four are signed answers from Peakstone and are final. `BAD_SIGNATURE` and `MALFORMED` mean that whatever
answered cannot be trusted.

Switch on the result with pattern matching; the compiler checks that every case is handled:

```java
switch (license.verify()) {
    case LicenseResult.Valid v       -> enableFeatures(v.plan());
    case LicenseResult.Offline o     -> enableFeatures(o.plan());
    case LicenseResult.Invalid i     -> disableFeatures(i.reason());
    case LicenseResult.Unavailable u -> warn(u.reason());
}
```

## Paper plugin example

Reads `license-key` from `config.yml`, verifies without blocking the main thread, disables the plugin (on the main
thread) when the licence is rejected, warns when Peakstone cannot be checked, and re-checks every six hours.

`src/main/resources/config.yml`:

```yaml
# Your licence key from peakstone.app, for example PS-7K3M-9QXA-2HDF-W8ZN
license-key: ""
```

`MyPlugin.java`:

```java
package com.example.myplugin;

import app.peakstone.license.LicenseResult;
import app.peakstone.license.PeakstoneLicense;
import java.time.Duration;
import java.util.logging.Level;
import org.bukkit.plugin.java.JavaPlugin;

public final class MyPlugin extends JavaPlugin {
    // Peakstone's Ed25519 public key (raw 32 bytes, base64). It is public; embedding it is fine.
    private static final String PEAKSTONE_PUBLIC_KEY = "<Peakstone public key>";

    private PeakstoneLicense.PeriodicChecks checks;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        PeakstoneLicense license;
        try {
            license = PeakstoneLicense.builder()
                    .product("my-plugin-slug")
                    .key(getConfig().getString("license-key"))
                    .publicKey("ps-1", PEAKSTONE_PUBLIC_KEY)
                    .dataDirectory(getDataFolder().toPath())
                    .build();
        } catch (IllegalArgumentException e) {
            // The key is missing or malformed. The message never contains the key itself.
            getLogger().severe("Check license-key in config.yml: " + e.getMessage());
            disableSelf();
            return;
        }

        // verifyAsync() runs on a virtual thread, so the main thread is never blocked.
        license.verifyAsync().whenComplete((result, error) -> {
            if (error != null) {
                getLogger().log(Level.WARNING, "Licence check failed unexpectedly", error);
            } else {
                handle(result);
            }
        });

        // Re-check every six hours. The first periodic check runs after one interval.
        checks = license.startPeriodicChecks(Duration.ofHours(6), this::handle);
    }

    @Override
    public void onDisable() {
        if (checks != null) {
            checks.close();
        }
    }

    /** Called on a virtual thread, not the main thread. */
    private void handle(LicenseResult result) {
        switch (result) {
            case LicenseResult.Valid v ->
                    getLogger().info("Licence verified (plan: " + v.plan() + ").");
            case LicenseResult.Offline o ->
                    getLogger().warning("Peakstone is unreachable; running on the saved licence until "
                            + o.expiresAt() + ".");
            case LicenseResult.Invalid i -> {
                getLogger().severe("Licence rejected (" + i.status() + "): " + i.reason());
                disableSelf();
            }
            case LicenseResult.Unavailable u ->
                    getLogger().warning("Could not check the licence (" + u.reason()
                            + "). The plugin keeps running and will try again.");
        }
    }

    /** The plugin manager must only be used from the main thread, so go through the scheduler. */
    private void disableSelf() {
        if (isEnabled()) {
            getServer().getScheduler().runTask(this,
                    () -> getServer().getPluginManager().disablePlugin(this));
        }
    }
}
```

This example was compile-checked against `paper-api` 1.21.11 (Java 21) and 26.2 (Java 25).

## Velocity plugin example

Velocity has no per-plugin config file API and plugins cannot be disabled at runtime, so this example reads the key
from `config.properties` in the plugin's data directory and, when the licence is rejected, unregisters the plugin's
listeners and flips a flag that its commands check.

```java
package com.example.myplugin;

import app.peakstone.license.LicenseResult;
import app.peakstone.license.PeakstoneLicense;
import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Properties;
import org.slf4j.Logger;

@Plugin(id = "my-plugin-slug", name = "MyPlugin", version = "1.0.0")
public final class MyPlugin {
    // Peakstone's Ed25519 public key (raw 32 bytes, base64). It is public; embedding it is fine.
    private static final String PEAKSTONE_PUBLIC_KEY = "<Peakstone public key>";

    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDirectory;

    private volatile boolean licensed = true;
    private PeakstoneLicense.PeriodicChecks checks;

    @Inject
    public MyPlugin(ProxyServer proxy, Logger logger, @DataDirectory Path dataDirectory) {
        this.proxy = proxy;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    /** Commands and listeners should check this before doing licensed work. */
    public boolean isLicensed() {
        return licensed;
    }

    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) {
        PeakstoneLicense license;
        try {
            license = PeakstoneLicense.builder()
                    .product("my-plugin-slug")
                    .key(readLicenseKey())
                    .publicKey("ps-1", PEAKSTONE_PUBLIC_KEY)
                    .dataDirectory(dataDirectory)
                    .build();
        } catch (IOException | IllegalArgumentException e) {
            logger.error("Check license-key in {}/config.properties: {}", dataDirectory, e.getMessage());
            reject();
            return;
        }

        license.verifyAsync().whenComplete((result, error) -> {
            if (error != null) {
                logger.warn("Licence check failed unexpectedly", error);
            } else {
                handle(result);
            }
        });
        checks = license.startPeriodicChecks(Duration.ofHours(6), this::handle);
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        if (checks != null) {
            checks.close();
        }
    }

    /** Called on a virtual thread. Velocity's managers are thread-safe, so no scheduler hop is needed. */
    private void handle(LicenseResult result) {
        switch (result) {
            case LicenseResult.Valid v -> {
                licensed = true;
                logger.info("Licence verified (plan: {}).", v.plan());
            }
            case LicenseResult.Offline o -> {
                licensed = true;
                logger.warn("Peakstone is unreachable; running on the saved licence until {}.", o.expiresAt());
            }
            case LicenseResult.Invalid i -> {
                logger.error("Licence rejected ({}): {}", i.status(), i.reason());
                reject();
            }
            case LicenseResult.Unavailable u ->
                    logger.warn("Could not check the licence ({}). The plugin keeps running and will try again.",
                            u.reason());
        }
    }

    private void reject() {
        licensed = false;
        proxy.getEventManager().unregisterListeners(this);
    }

    private String readLicenseKey() throws IOException {
        Files.createDirectories(dataDirectory);
        Path file = dataDirectory.resolve("config.properties");
        if (Files.notExists(file)) {
            Files.writeString(file, "# Your licence key from peakstone.app\nlicense-key=\n");
        }
        Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            properties.load(in);
        }
        return properties.getProperty("license-key");
    }
}
```

This example was compile-checked against `velocity-api` 4.2.0.

## Choosing a policy

The SDK reports what it found; what to do about it is your decision. The examples use these defaults:

- **`Invalid` disables the plugin.** The licence was refused, or whatever answered cannot be trusted.
- **`Unavailable` only warns.** Peakstone being down, or the server having no internet, should not break a paying
  customer's server. Switch on `Unavailable` and disable the plugin if you prefer to fail closed.
- **`Offline` runs normally**, with a warning in the log.

`BAD_SIGNATURE` and `MALFORMED` are also `Invalid`. They usually mean a captive portal, a proxy or a wrong system
clock rather than a bad licence. If you would rather not disable the plugin for those, handle them like
`Unavailable`:

```java
case LicenseResult.Invalid i when i.status() == LicenseResult.Status.BAD_SIGNATURE
        || i.status() == LicenseResult.Status.MALFORMED -> warn(i.reason());
case LicenseResult.Invalid i -> disable(i.reason());
```

Do not call `verify()` on the main thread: it blocks for up to the timeout. Use `verifyAsync()`, or call it from
your own async task.

## How it works

Endpoint: `POST {baseUrl}/api/v1/licenses/verify`, default base URL `https://peakstone.app`.

Request headers: `content-type: application/json` and `user-agent: peakstone-license-java/0.2.0 (<product>)`.

```json
{ "key": "PS-7K3M-9QXA-2HDF-W8ZN", "product": "my-plugin-slug", "instance": "<uuid>", "nonce": "<random base64url>",
  "version": "<your plugin version, optional>", "sdk": "java/0.2.0" }
```

- `instance` is a random UUID created on first use and stored in `<dataDirectory>/peakstone-instance`. It
  identifies this installation.
- `nonce` is 24 fresh random bytes (32 base64url characters) per request.
- The key is sent in its normalised form: upper case, whitespace removed.

Response `200`:

```json
{ "payload": "<base64url, no padding>", "signature": "<base64url, no padding>", "keyId": "ps-1" }
```

`payload` is UTF-8 JSON; `signature` is an Ed25519 signature over those exact bytes.

```json
{
  "v": 1, "valid": true, "status": "active", "reason": null,
  "product": "my-plugin-slug", "license": "PS-7K3M-9QXA-2HDF-W8ZN",
  "instance": "<uuid>", "nonce": "<same nonce>", "plan": "Network",
  "issuedAt": 1767225600, "expiresAt": 1767484800, "periodEnd": 1769904000
}
```

When the licence is not valid, `valid` is `false`, `status` is one of `unknown`, `inactive`, `revoked` or
`wrong_product`, `reason` is a short sentence, and the response is still signed.

Other statuses (`429` with a `retry-after` header in seconds, `400`, `5xx`) are not signed and are treated as "could
not verify now".

### What the SDK checks

A response is accepted only if all of these hold:

1. The signature verifies with a configured public key (the one registered for the response's `keyId`, or any key
   registered without an id).
2. `v` is `1`.
3. `product` equals your configured product.
4. `license` equals your key, compared after upper-casing and removing spaces.
5. `instance` equals this installation's id.
6. `nonce` equals the nonce just sent (live responses only).
7. `issuedAt` is not more than 5 minutes in the future of the local clock, and `expiresAt` is in the future.

A `valid: true` payload must carry `issuedAt` and `expiresAt`. For `valid: false` payloads those two are checked only
if present.

Mapping to results: a failed signature or a mismatch in the licence, instance, nonce or time window is
`Invalid(BAD_SIGNATURE)`; a different product is `Invalid(WRONG_PRODUCT)`; an unparseable response or unsupported
version is `Invalid(MALFORMED)`. Only responses that pass all checks can produce `Valid` or a signed
`Invalid(UNKNOWN | INACTIVE | REVOKED | WRONG_PRODUCT | LIMIT_REACHED)`.

## Offline grace

After every live `Valid` response, the raw response (payload, signature, keyId) is written to
`<dataDirectory>/peakstone-license.json`. The write is atomic (temporary file in the same directory, then moved
into place), so a crash cannot leave a half-written cache.

If a later check cannot get an answer, the cached response is loaded and verified again: signature, version,
product, licence, instance, time window, and that it says `valid: true`. Only the nonce is not checked, since a
stored response cannot match a fresh one. While `expiresAt` is in the future the result is `Offline`.

"Cannot get an answer" means a network error, a timeout, `429`, `5xx`, and also any other non-`200` status
(including `400` and redirects, which are not followed). A response that arrives but cannot be trusted
(`BAD_SIGNATURE`, `MALFORMED`) is not a "cannot get an answer" and never falls back to the cache.

A signed `Invalid` response is final: it deletes the cache and the result is `Invalid` immediately. It is never
masked by the cache. An untrusted response (bad signature, wrong nonce) does not delete the cache, so nobody can
wipe it by injecting garbage.

If no usable cache exists the result is `Unavailable`.

Files in the data directory:

| File | Content |
| --- | --- |
| `peakstone-instance` | This installation's random UUID. Delete it and the next check uses a new one; the old cache then no longer matches and is ignored. |
| `peakstone-license.json` | The last valid response, as received. |

If the data directory cannot be written, checks still work online: the SDK uses a temporary instance id for that
run and logs a warning, and nothing is cached.

## Security notes

- Signatures stop **forged and replayed answers**: a fake `peakstone.app` (hosts file, DNS, proxy) cannot produce a
  valid signature, and an old response does not match a new request's nonce.
- A licence check inside a plugin jar cannot stop someone from **patching the check out of the jar**. Treat the SDK
  as a way to keep honest customers honest and to cut off cancelled subscriptions, not as copy protection. Obfuscation
  and keeping important logic out of the plugin make bypassing harder, but never impossible.
- Offline grace trusts the **local clock**. A server owner who freezes the clock can stay in the grace period. The
  cache is rejected if the clock is moved back to before it was issued (more than 5 minutes of skew).
- The licence key is never logged or exposed: `toString()` and all log messages use `PS-7K3M-****-****-W8ZN`.
  The cache file does contain the key inside the signed payload, so keep your plugin's data directory private, as
  you would `config.yml` where the key is also stored.
- The SDK logs through `System.Logger` (named `app.peakstone.license`, which becomes the relocated package name
  when shaded). On Paper and Velocity this ends up in the server log.
- Responses are limited to 64 KiB. Redirects are not followed.

## API summary

```java
public final class PeakstoneLicense {
    static Builder builder();
    LicenseResult verify();                                        // blocking
    CompletableFuture<LicenseResult> verifyAsync();                // virtual thread
    PeriodicChecks startPeriodicChecks(Duration interval, Consumer<? super LicenseResult> onResult);

    interface PeriodicChecks extends AutoCloseable { void close(); }   // close() does not throw

    final class Builder {
        Builder product(String slug);
        Builder key(String licenseKey);
        Builder publicKey(String base64);
        Builder publicKey(String keyId, String base64);
        Builder dataDirectory(Path dir);
        Builder baseUrl(URI baseUrl);
        Builder pluginVersion(String pluginVersion);
        Builder timeout(Duration timeout);
        PeakstoneLicense build();
    }
}

public sealed interface LicenseResult {
    record Valid(String plan, Instant expiresAt, Instant periodEnd) implements LicenseResult {}
    record Offline(String plan, Instant expiresAt) implements LicenseResult {}
    record Invalid(Status status, String reason) implements LicenseResult {}
    record Unavailable(String reason, Duration retryAfter) implements LicenseResult {}

    enum Status { UNKNOWN, INACTIVE, REVOKED, WRONG_PRODUCT, LIMIT_REACHED, BAD_SIGNATURE, MALFORMED }

    default boolean allowsUse();   // true for Valid and Offline
}
```

`startPeriodicChecks`:

- The first check runs after one `interval`, so call `verify()`/`verifyAsync()` yourself at startup.
- The callback runs on a virtual thread, not the server's main thread.
- If Peakstone answers `429` with a longer `retry-after` than `interval`, the next check waits that long.
- Exceptions thrown by the callback are logged and do not stop the checks.
- Close the returned handle (for example in `onDisable`) to stop them. It is safe to close more than once.

## Why the bytecode is Java 21 but the build uses JDK 25

Paper for Minecraft 1.21.x runs on Java 21; newer Minecraft versions need Java 25. A plugin that depends on this SDK
has to load on both, and a class file compiled for Java 25 does not load on Java 21.

So the SDK is built with **JDK 25** but compiled with `--release 21` (`maven.compiler.release=21`):

- The class files are version 65 (Java 21) and run unchanged on Java 21 and anything newer.
- `--release` (unlike plain `-source`/`-target`) also restricts the compiler to the Java 21 API, so using a method that
  only exists in Java 22+ is a compile error instead of a `NoSuchMethodError` on a 1.21.x server.
- Building with the newest LTS JDK keeps the toolchain current while the output stays compatible.

Everything in the SDK is plain Java 21: records, sealed interfaces, pattern matching for `switch`, virtual threads,
`java.net.http.HttpClient` and the JDK's built-in Ed25519. Your own plugin only has to target Java 21 as well (for
example `<maven.compiler.release>21</maven.compiler.release>`) to stay loadable on both JVMs. Which Minecraft versions
it supports is then decided by the Paper API you compile against.

## Building and testing

```sh
JAVA_HOME=/path/to/jdk-25 mvn verify
```

This compiles the main code with `-Xlint:all -Werror`, runs the tests and builds
`target/peakstone-license-0.2.0.jar` and `target/peakstone-license-0.2.0-sources.jar`. JDK 21 or newer works for
building; the release is fixed at 21 either way. Maven 3.9.x itself prints a few `sun.misc.Unsafe` warnings when it
runs on JDK 25; they come from Maven's own dependencies, not from compiling the SDK.

The tests are fully offline. They start a fake Peakstone (`com.sun.net.httpserver.HttpServer` on localhost) that
signs responses with an Ed25519 key pair generated in the test, and cover valid, invalid, tampered, replayed and
mismatched responses, expiry, offline grace, rate limiting, key rotation, malformed input, key masking, atomic
cache writes and the periodic checks.

## Licence

MIT. See [LICENSE](LICENSE).
