package day.alacraft.alalogger.api;

import day.alacraft.alalogger.ChatText;

import java.time.Duration;
import java.util.Optional;

/**
 * Why a call failed, in the form the caller has to act on.
 *
 * @param status     HTTP status, or {@code 0} when no response was ever received.
 * @param code       the case to branch on.
 * @param rawCode    exactly what the site sent in {@code error_code}, empty when it sent none.
 *                   Kept next to {@link #code} so a code added after this build ships still
 *                   reaches the log file instead of vanishing into {@link ApiErrorCode#UNKNOWN}.
 * @param message    an English sentence for a log file. Not for the player: the site's wording
 *                   can change, and player-facing text belongs in the mod's own translations,
 *                   keyed off {@link #code}.
 * @param retryAfter how long to wait, when the site said. Present in practice only on
 *                   {@link ApiErrorCode#RATE_LIMITED}.
 */
public record ApiError(
        int status,
        ApiErrorCode code,
        String rawCode,
        String message,
        Optional<Duration> retryAfter) {

    public ApiError {
        code = code == null ? ApiErrorCode.UNKNOWN : code;
        rawCode = rawCode == null ? "" : rawCode;
        // Meant for a log file, but it does reach chat: an error nothing else
        // explains is shown to the player as "reason: <this>". The site wrote it,
        // and the site is whatever apiBaseUrl points at.
        message = ChatText.plain(message);
        retryAfter = retryAfter == null ? Optional.empty() : retryAfter;
    }

    /** A failure that happened on this side, before or instead of an HTTP response. */
    static ApiError client(ApiErrorCode code, String message) {
        return new ApiError(0, code, code.wireName(), message, Optional.empty());
    }

    /** True when the request never reached the site, so nothing changed there. */
    public boolean isTransport() {
        return code.isTransport();
    }

    /** True when the same request could plausibly succeed later. */
    public boolean isRetryable() {
        return code.isRetryable();
    }

    /** Seconds to wait before retrying, or {@code fallback} when the site did not say. */
    public long retryAfterSeconds(long fallback) {
        return retryAfter.map(Duration::toSeconds).orElse(fallback);
    }
}
