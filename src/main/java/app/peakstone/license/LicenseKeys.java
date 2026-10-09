package app.peakstone.license;

import java.util.Locale;
import java.util.regex.Pattern;

/** Normalising, validating and masking licence keys such as {@code PS-7K3M-9QXA-2HDF-W8ZN}. */
final class LicenseKeys {
    /** {@code PS-} followed by four groups of four Crockford base32 characters (no I, L, O, U). */
    private static final Pattern FORMAT =
            Pattern.compile("PS-[0-9A-HJKMNP-TV-Z]{4}(?:-[0-9A-HJKMNP-TV-Z]{4}){3}");

    private LicenseKeys() {}

    /** Upper-cases and removes all whitespace, so " ps-7k3m-... " becomes "PS-7K3M-...". */
    static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (!Character.isWhitespace(c) && !Character.isSpaceChar(c)) {
                sb.append(c);
            }
        }
        return sb.toString().toUpperCase(Locale.ROOT);
    }

    /** Whether an already normalised key has the expected format. */
    static boolean isValid(String normalized) {
        return FORMAT.matcher(normalized).matches();
    }

    /** Masks the middle of a valid, normalised key: {@code PS-7K3M-****-****-W8ZN}. */
    static String mask(String normalized) {
        return normalized.substring(0, 7) + "-****-****-" + normalized.substring(18);
    }
}
