# Paper example: Peakstone licence check

A minimal Paper plugin (`LicenceDemo`) that checks a Peakstone licence key with the
[Peakstone licence SDK](https://github.com/Jan1k1/peakstone-sdk). Copy it as a starting point. MIT licensed.

What it does:

- reads `license-key` from `config.yml`;
- checks the key when the plugin enables, without blocking the main thread, and again every six hours;
- disables itself with `Licence rejected (STATUS): reason` when Peakstone refuses the key (unknown, revoked, inactive,
  wrong plugin, server limit reached, pulled version, or an answer that fails the signature check);
- keeps running with a warning when Peakstone cannot be reached, on the saved signed answer while it is valid
  (`Peakstone is unreachable; running on the saved licence until ...`) and after that too (`Could not check the
  licence ...`). If you prefer to fail closed, disable the plugin on `Unavailable` as well.

Targets Java 21 (Paper 1.21.x) and also loads on Java 25 servers. The SDK is shaded and relocated into
`com.example.licensedemo.libs.peakstone`, so the plugin is a single jar that cannot clash with another plugin's copy.

## Make it yours

1. Rename the package `com.example.licensedemo` (also in `pom.xml`, the relocation, and `plugin.yml`).
2. Set `PRODUCT` in `LicenceDemoPlugin.java` to your plugin's slug on Peakstone.
3. Check that `PEAKSTONE_PUBLIC_KEY` matches the key on <https://peakstone.app/docs/licensing#public-key>.

## Build

The SDK is on GitHub Packages, which needs a GitHub token with `read:packages` even to read (see the licensing docs), or
install it locally from the SDK repository with `mvn install`. Then, with JDK 21 or newer:

```sh
mvn package
```

The plugin is `target/licence-demo-1.0.0.jar`.

## Test against a local Peakstone

A normal build always checks `https://peakstone.app` with Peakstone's production key, and server owners cannot change
that: there is no URL or key in `config.yml`. To try the plugin against a Peakstone you run locally, build a separate
test jar with the `local-peakstone` profile:

```sh
mvn package -Plocal-peakstone \
    -Dpeakstone.test.baseUrl=http://localhost:3000 \
    -Dpeakstone.test.publicKey="$(curl -s http://localhost:3000/api/v1/licenses/keys | sed 's/.*"publicKey":"\([^"]*\)".*/\1/')"
```

The profile refuses to build unless both values are given and the URL is a loopback `http://localhost:PORT` or
`http://127.0.0.1:PORT`; the plugin checks that again at runtime and logs `TEST BUILD: ... Do not ship this jar.` on
every start. A test jar re-checks every 20 seconds instead of six hours. A local Peakstone without
`LICENSE_SIGNING_KEY` makes a new signing key on every restart, so rebuild the test jar after restarting it.

On a test server, set `online-mode=false` in `server.properties` only if it is bound to `127.0.0.1`.
