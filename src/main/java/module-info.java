/**
 * Peakstone licence SDK for Minecraft plugins.
 *
 * <p>Plugins normally shade and relocate this library, in which case the module descriptor is
 * ignored. It is provided for applications that use the module path.
 */
module app.peakstone.license {
    requires java.net.http;

    exports app.peakstone.license;
}
