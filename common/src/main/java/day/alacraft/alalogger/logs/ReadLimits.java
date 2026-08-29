package day.alacraft.alalogger.logs;

/**
 * How much of a file may be uploaded.
 *
 * <p>The values are the server's to decide, not ours: they come from
 * {@code GET /limits} so that raising the cap on alacraft.day does not need a
 * mod release, and so a self-hosted instance can set its own. The constants
 * here are only the fallback for the first upload after a cold start, or for
 * when the limits endpoint cannot be reached.
 *
 * <p>Non-positive values are replaced with the defaults rather than honoured.
 * A limits response that arrives malformed — a proxy returning an error page,
 * a typo in a self-hosted config — would otherwise produce an empty upload,
 * and an empty upload fails at the far end with a message about the file
 * instead of about the limits.
 */
public record ReadLimits(long maxBytes, int maxLines) {

    /** What alacraft.day currently accepts. */
    public static final long DEFAULT_MAX_BYTES = 10L * 1024 * 1024;

    public static final int DEFAULT_MAX_LINES = 25_000;

    public static final ReadLimits DEFAULT = new ReadLimits(DEFAULT_MAX_BYTES, DEFAULT_MAX_LINES);

    /**
     * The most this mod will read into memory, whatever the server says.
     *
     * <p>Six times the current limit, so raising the cap on the site still works
     * without a mod release — but a response of {@code {"maxLength": 9000000000}},
     * from a broken self-hosted config or a proxy returning somebody else's
     * JSON, cannot make the reader pull a 400 MB log into the heap. The
     * "memory stays bounded by the limits" promise in LogReader has to be
     * bounded by something we control, not by the goodwill of the endpoint.
     */
    public static final long MAX_ALLOWED_BYTES = 64L * 1024 * 1024;

    public static final int MAX_ALLOWED_LINES = 1_000_000;

    public ReadLimits {
        if (maxBytes <= 0) {
            maxBytes = DEFAULT_MAX_BYTES;
        }
        if (maxLines <= 0) {
            maxLines = DEFAULT_MAX_LINES;
        }

        maxBytes = Math.min(maxBytes, MAX_ALLOWED_BYTES);
        maxLines = Math.min(maxLines, MAX_ALLOWED_LINES);
    }
}
