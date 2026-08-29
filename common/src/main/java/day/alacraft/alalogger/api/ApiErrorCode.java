package day.alacraft.alalogger.api;

/**
 * Every distinct way a call can fail, as one closed set.
 *
 * <p>The first twelve constants mirror the {@code error_code} field the site
 * puts in its error bodies; the rest are produced here, for failures that never
 * reach the site or come back unreadable. They live in the same enum on purpose:
 * the caller's question is always "what do I tell the player", and that has one
 * answer per constant regardless of which side produced it. A single exhaustive
 * {@code switch} covers "wait 40 seconds", "your log is too big", "check the
 * URL" and "no internet" without an instanceof ladder in front of it.
 *
 * <p>Whether the site was reached at all is not lost in the merge — see
 * {@link #isTransport()} and {@link ApiError#status()}.
 *
 * <p>Unrecognised wire codes become {@link #UNKNOWN} rather than an exception:
 * the site may add a code before the mod knows about it, and an upload must not
 * fail differently because of a string we have not seen before.
 */
public enum ApiErrorCode {

    // ---- Reported by the site, in the error_code field ----

    /** {@code content} was missing, empty, or not a string. */
    INVALID_CONTENT("invalid_content"),

    /** A metadata entry broke the shape or the caps (20 entries, key 64, value 500). */
    INVALID_METADATA("invalid_metadata"),

    /** The request body was not the shape the endpoint expects. */
    INVALID_REQUEST("invalid_request"),

    /** Wrong or missing token. On delete, that is the log's own token, not an account one. */
    INVALID_TOKEN("invalid_token"),

    /** The account token is real but not allowed to upload logs. */
    INSUFFICIENT_SCOPE("insufficient_scope"),

    /** No such log, or it expired. Retention runs from the last read, not from creation. */
    NOT_FOUND("not_found"),

    /** A moderator took the log down. Distinct from {@link #NOT_FOUND} — it existed. */
    REMOVED("removed"),

    /**
     * The body was refused for size. Note this can come from the site or from a
     * proxy in front of it: the application truncates oversized logs rather than
     * rejecting them, so a 413 usually means the request never got that far.
     */
    TOO_LARGE("too_large"),

    /** More items in one bulk call than the endpoint accepts. */
    TOO_MANY_ITEMS("too_many_items"),

    /** The compressed body could not be decompressed. */
    MALFORMED_BODY("malformed_body"),

    /**
     * The instance does not understand the {@code Content-Encoding} we sent.
     * Only reachable against an older or self-hosted instance; see
     * {@link AlaLoggerApi.Builder#compress(boolean)} for the way out.
     */
    UNSUPPORTED_ENCODING("unsupported_encoding"),

    /**
     * Rate limited. {@link ApiError#retryAfter()} usually carries how long to
     * wait. Anonymous uploads share one allowance per IP address, which on
     * shared hosting is shared with strangers — an account token moves the
     * upload onto its own, larger allowance.
     */
    RATE_LIMITED("rate_limited"),

    // ---- Derived here, from a response that carried no usable code ----

    /** The site answered 5xx. Its problem, not the caller's: worth retrying. */
    SERVER_ERROR("server_error"),

    /** An answer arrived that was not the JSON this API promises — a proxy error page, most likely. */
    MALFORMED_RESPONSE("malformed_response"),

    /** An error we do not recognise. {@link ApiError#rawCode()} keeps what the site actually said. */
    UNKNOWN("unknown"),

    // ---- Produced here, without ever reaching the site ----

    /** The site could not be reached at all: refused, unroutable, or an unresolvable host. */
    OFFLINE("network_offline"),

    /** Reached, but out of time before it answered. */
    TIMEOUT("network_timeout"),

    /** The TLS handshake failed — a certificate or protocol problem, not a missing network. */
    TLS("network_tls"),

    /** A bug on this side. If a player ever sees this, we wrote it wrong. */
    INTERNAL("client_internal");

    private final String wireName;

    ApiErrorCode(String wireName) {
        this.wireName = wireName;
    }

    /** The string form: what the site sends, or a matching name for the client-side codes. */
    public String wireName() {
        return wireName;
    }

    /** The code for a wire string, or {@link #UNKNOWN} for anything unrecognised. */
    public static ApiErrorCode of(String wireName) {
        if (wireName != null && !wireName.isBlank()) {
            for (ApiErrorCode code : values()) {
                if (code.wireName.equals(wireName)) {
                    return code;
                }
            }
        }
        return UNKNOWN;
    }

    /** True when the request never got an answer, so nothing was uploaded or deleted. */
    public boolean isTransport() {
        return this == OFFLINE || this == TIMEOUT || this == TLS;
    }

    /**
     * True when the same request could plausibly succeed later.
     *
     * <p>Deliberately narrow. Re-sending a log that was refused as too large, or
     * with a token the site rejected, only wastes the user's rate limit.
     */
    public boolean isRetryable() {
        return this == RATE_LIMITED || this == SERVER_ERROR || this == OFFLINE || this == TIMEOUT;
    }
}
