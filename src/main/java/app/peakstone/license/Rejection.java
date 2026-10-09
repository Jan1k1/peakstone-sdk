package app.peakstone.license;

import app.peakstone.license.LicenseResult.Status;

/**
 * Internal control-flow exception: a response was received but must not be trusted or used. It
 * carries the {@link Status} to report. Messages never contain the licence key.
 */
final class Rejection extends Exception {
    private static final long serialVersionUID = 1L;

    private final Status status;

    Rejection(Status status, String message) {
        super(message, null, false, false); // no stack trace: this is expected control flow
        this.status = status;
    }

    Status status() {
        return status;
    }

    LicenseResult.Invalid toResult() {
        return new LicenseResult.Invalid(status, getMessage());
    }
}
