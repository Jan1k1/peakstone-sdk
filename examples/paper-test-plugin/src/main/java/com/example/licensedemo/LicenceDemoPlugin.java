package com.example.licensedemo;

import app.peakstone.license.LicenseResult;
import app.peakstone.license.PeakstoneLicense;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.util.Properties;
import java.util.logging.Level;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * A minimal paid plugin that checks its Peakstone licence key: on startup and every six hours. It disables
 * itself when Peakstone rejects the key and keeps running (with a warning) when Peakstone cannot be reached.
 *
 * <p>Copy it, then change {@link #PRODUCT} to your plugin's slug on Peakstone and the package names.
 */
public final class LicenceDemoPlugin extends JavaPlugin {
    /** Your plugin's slug on Peakstone: the last part of https://peakstone.app/plugins/&lt;slug&gt;. */
    private static final String PRODUCT = "my-plugin-slug";

    /**
     * Peakstone's Ed25519 public key and its id, from https://peakstone.app/docs/licensing#public-key.
     * It is public: embedding it is fine, and it is what makes a fake Peakstone useless.
     */
    private static final String PEAKSTONE_KEY_ID = "ps-1";
    private static final String PEAKSTONE_PUBLIC_KEY = "xNBoJlnIjUeloKkicsJjrkUv4CXxzM/tMjGLkDC0PP4=";

    private static final Duration CHECK_INTERVAL = Duration.ofHours(6);

    private PeakstoneLicense.PeriodicChecks checks;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        TestBuild test = TestBuild.load(this);

        PeakstoneLicense license;
        try {
            PeakstoneLicense.Builder builder = PeakstoneLicense.builder()
                    .product(PRODUCT)
                    .key(getConfig().getString("license-key"))
                    .dataDirectory(getDataFolder().toPath())
                    .pluginVersion(getPluginMeta().getVersion()); // Bukkit: getDescription().getVersion()
            if (test == null) {
                builder.publicKey(PEAKSTONE_KEY_ID, PEAKSTONE_PUBLIC_KEY);
            } else {
                builder.baseUrl(test.baseUrl()).publicKey(test.keyId(), test.publicKey());
            }
            license = builder.build();
        } catch (IllegalArgumentException e) {
            // The key is missing or malformed. The message never contains the key itself.
            getLogger().severe("Check license-key in config.yml: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this); // still on the main thread here
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

        // The first periodic check runs after one interval; the one above covers startup.
        checks = license.startPeriodicChecks(test == null ? CHECK_INTERVAL : test.interval(), this::handle);
    }

    @Override
    public void onDisable() {
        if (checks != null) {
            checks.close();
            checks = null;
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
            getServer().getScheduler().runTask(this, () -> getServer().getPluginManager().disablePlugin(this));
        }
    }

    /**
     * Settings of a jar built with the {@code local-peakstone} Maven profile, which checks licences against a
     * Peakstone running on this machine. Normal builds leave peakstone-build.properties empty and get {@code null}
     * here, so a server owner cannot point a release jar at another server: the URL and key are inside the jar,
     * not in config.yml, and only loopback http addresses are accepted.
     */
    private record TestBuild(URI baseUrl, String keyId, String publicKey, Duration interval) {
        static TestBuild load(JavaPlugin plugin) {
            Properties p = new Properties();
            try (InputStream in = LicenceDemoPlugin.class.getResourceAsStream("/peakstone-build.properties")) {
                if (in != null) {
                    p.load(in);
                }
            } catch (IOException e) {
                return null;
            }
            String url = p.getProperty("test.baseUrl", "").strip();
            if (url.isEmpty()) {
                return null;
            }
            URI uri = URI.create(url);
            String host = uri.getHost();
            if (!"http".equals(uri.getScheme()) || !("localhost".equals(host) || "127.0.0.1".equals(host))) {
                throw new IllegalStateException("peakstone-build.properties: test.baseUrl must be a local http address");
            }
            String seconds = p.getProperty("test.checkSeconds", "").strip();
            Duration interval = Duration.ofSeconds(seconds.isEmpty() ? 20 : Math.max(5, Long.parseLong(seconds)));
            plugin.getLogger().warning("TEST BUILD: licences are checked against " + uri + " every "
                    + interval.toSeconds() + "s. Do not ship this jar.");
            return new TestBuild(uri, p.getProperty("test.keyId", "ps-1").strip(),
                    p.getProperty("test.publicKey", "").strip(), interval);
        }
    }
}
