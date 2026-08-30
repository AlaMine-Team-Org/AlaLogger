package day.alacraft.alalogger.api;

import day.alacraft.alalogger.AlaLogger;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Optional;

/**
 * What counts as a usable API base URL, decided in one place.
 *
 * <p>Two callers need this answer and want opposite things when it is no. The
 * config file repairs itself and carries on, because a server must start even
 * with a typo in it; {@link AlaLoggerApi.Builder} refuses to be built, because a
 * client pointed at nothing is not a client. Both used to carry their own copy
 * of the trimming and the checks, and the copies did not agree: the config was
 * happy with {@code alacraft.day/api/v1}, the builder threw on it, and the throw
 * came out of the mod's entrypoint — so a missing {@code https://} stopped the
 * whole server from starting.
 *
 * <p>Answering once, and letting each caller decide what to do with a
 * {@code no}, is what keeps that from happening again.
 *
 * <p><b>The answer is rebuilt, not echoed.</b> Every path is appended to this
 * value by concatenation, so anything after the path in a URL swallows what
 * comes next. A base URL ending in {@code #notes} sent every request — upload,
 * limits, insights, delete — to the same address, because the appended
 * {@code /logs} landed inside the fragment and was never transmitted. A query
 * string does the same, one degree less completely. Neither means anything for
 * an API base, so both are dropped rather than obeyed.
 */
public final class ApiEndpoint {

    private ApiEndpoint() {
    }

    /**
     * The URL as a client should use it, or empty when it cannot be used at all.
     *
     * <p>Trailing slashes go because every path is appended with a leading one,
     * and {@code .../api/v1//logs} is a 404 on one server and a redirect on the
     * next — and a redirected POST loses the log body silently.
     *
     * <p>Only the scheme, the host and the port are checked. Anything more —
     * that the host resolves, that it answers — is a network question, and
     * asking it here would mean a DNS lookup while the game is still loading.
     */
    public static Optional<String> normalise(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }

        URI parsed;

        try {
            parsed = new URI(raw.trim());
        } catch (URISyntaxException e) {
            return Optional.empty();
        }

        String scheme = parsed.getScheme() == null ? "" : parsed.getScheme().toLowerCase(Locale.ROOT);

        if (!scheme.equals("http") && !scheme.equals("https") || parsed.getHost() == null) {
            return Optional.empty();
        }

        int port = parsed.getPort();

        // java.net.URI parses the digits without judging them, so :99999 arrives
        // here intact and fails much later — as an IllegalArgumentException from
        // the HTTP client on the first request, which reaches the player as an
        // internal error rather than as "fix your config".
        if (port != -1 && (port < 1 || port > 65535)) {
            return Optional.empty();
        }

        if (parsed.getRawUserInfo() != null) {
            // Worth a word rather than a silent drop: the operator believes they
            // have configured authentication. They have not — the JDK's HTTP
            // client discards userinfo, so those credentials were never sent
            // even before this method removed them. Keeping them in the value
            // only meant printing them into latest.log, which is the file this
            // mod uploads.
            AlaLogger.LOGGER.warn("The credentials in apiBaseUrl are ignored - an HTTP client does not send "
                    + "them. Use the apiToken setting instead. They have been dropped from the address so "
                    + "they cannot reach a log file.");
        }

        StringBuilder rebuilt = new StringBuilder()
                .append(parsed.getScheme())
                .append("://")
                .append(parsed.getHost());

        if (port != -1) {
            rebuilt.append(':').append(port);
        }

        if (parsed.getRawPath() != null) {
            rebuilt.append(parsed.getRawPath());
        }

        while (rebuilt.length() > 0 && rebuilt.charAt(rebuilt.length() - 1) == '/') {
            rebuilt.setLength(rebuilt.length() - 1);
        }

        return Optional.of(rebuilt.toString());
    }
}
