package day.alacraft.alalogger.api;

import com.google.gson.JsonObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.zip.GZIPOutputStream;

import javax.net.ssl.SSLException;

/**
 * The mod's connection to the AlaCraft Log Checker API.
 *
 * <p>Build one per process and keep it: it owns an {@link HttpClient}, a thread
 * pool and the cached limits. Every method returns immediately with a {@link
 * CompletableFuture}; nothing here may ever be called on a thread the game needs
 * back, which is why even the gzip pass is handed to the executor rather than
 * run on the caller.
 *
 * <p>Uploads are compressed. A Minecraft log is the most compressible payload
 * there is — roughly tenfold — and the machine sending one is, by definition, a
 * machine having a bad day. The site decompresses before anything else reads the
 * body, so this is invisible apart from the bandwidth.
 *
 * <p><b>Bearer means two different things.</b> On upload it is the player's
 * personal account token from /profile, which attaches the log to their history
 * and moves them off the shared per-IP rate limit. On delete it is the one-time
 * token that came back with that particular log. They are never interchangeable,
 * so the account token lives on the client and the log token is a parameter.
 */
public final class AlaLoggerApi implements AutoCloseable {

    /** Long enough for a slow DNS answer, short enough that a dead host is not a hang. */
    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(10);

    /**
     * Generous on purpose: the server is uploading megabytes from a home
     * connection while it is itself under load, and losing the log to an
     * impatient timeout is worse than waiting.
     */
    private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(60);

    /** How long a shutdown waits for an upload in flight before cutting it off. */
    private static final Duration SHUTDOWN_GRACE = Duration.ofSeconds(5);

    /**
     * The site's id alphabet with room to spare. Ids reach us from a config file
     * or a chat argument, so this is also what keeps {@code ../} out of a URL we
     * build by concatenation.
     */
    private static final Pattern LOG_ID = Pattern.compile("[A-Za-z0-9_-]{1,32}");

    private final HttpClient http;
    private final ExecutorService executor;
    private final String baseUrl;
    private final String userAgent;
    private final String source;
    private final String apiToken;
    private final String language;
    private final Duration requestTimeout;
    private final boolean compress;

    /**
     * Holds the in-flight or completed {@link #limits()} call. A failed one is
     * evicted, so a lookup that failed while the site was down is retried rather
     * than remembered for the rest of the session.
     */
    private final AtomicReference<CompletableFuture<Limits>> limitsCache = new AtomicReference<>();

    private AlaLoggerApi(Builder builder) {
        this.baseUrl = builder.baseUrl;
        this.userAgent = builder.userAgent;
        this.source = builder.source;
        this.apiToken = builder.apiToken;
        this.language = builder.language;
        this.requestTimeout = builder.requestTimeout;
        this.compress = builder.compress;

        // Virtual threads: one per request, no pool to size, and daemon by
        // nature — a forgotten close() cannot keep the JVM from exiting.
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
        this.http = HttpClient.newBuilder()
                .connectTimeout(builder.connectTimeout)
                .executor(executor)
                // Redirects are NOT followed. The JDK would turn a redirected
                // POST into a GET and the log would vanish silently; surfacing
                // the 3xx lets us say "check the base URL" instead.
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public static Builder builder(String baseUrl) {
        return new Builder(baseUrl);
    }

    /**
     * The User-Agent the site asks automated clients to send, e.g.
     * {@code AlaLogger/1.0.0 (Minecraft 26.2; fabric)}.
     *
     * <p>It is how our traffic is told apart from a scraper's when someone is
     * looking at why uploads are failing, so it is worth getting right. Anything
     * that cannot travel in a header is replaced rather than rejected: a version
     * string is not worth failing an upload over.
     */
    public static String userAgent(String version, String minecraftVersion, String loader) {
        return "AlaLogger/" + headerSafe(version, "dev")
                + " (Minecraft " + headerSafe(minecraftVersion, "unknown")
                + "; " + headerSafe(loader, "unknown") + ")";
    }

    /** Upload a log with the client's defaults for source and language. */
    /**
     * The host this client talks to, for messages that have to name it.
     *
     * <p>"Could not reach ..." has to say the address the operator configured.
     * Someone running their own instance changed {@code apiBaseUrl} precisely
     * because they do not use alacraft.day, and naming ours sends them to check
     * a machine that is not theirs.
     *
     * <p>Never null and never empty: {@link Builder} rejects a base URL without
     * an http/https scheme and a host, so by the time a client exists the answer
     * is known good.
     */
    public String host() {
        return URI.create(baseUrl).getHost();
    }

    public CompletableFuture<UploadResult> upload(String content) {
        return upload(UploadRequest.of(content));
    }

    /**
     * Upload a log.
     *
     * <p>The returned future fails with an {@link ApiException} and nothing else.
     * The interesting cases are {@link ApiErrorCode#RATE_LIMITED} (with a
     * {@link ApiError#retryAfter()}), {@link ApiErrorCode#TOO_LARGE} and
     * {@link ApiErrorCode#OFFLINE} — a server whose network is down is a very
     * ordinary reason to be uploading a log in the first place.
     */
    public CompletableFuture<UploadResult> upload(UploadRequest request) {
        UploadRequest effective = request.withDefaults(source, language);
        URI uri = uploadUri(effective.language());

        // Serialising and gzipping ten megabytes is not free, and the thread
        // that asked for the upload is usually the one running the game.
        return CompletableFuture
                .supplyAsync(() -> encode(effective.toJson()), executor)
                .thenCompose(body -> {
                    HttpRequest.Builder post = request(uri)
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofByteArray(body));

                    if (compress) {
                        post.header("Content-Encoding", "gzip");
                    }

                    if (!apiToken.isEmpty()) {
                        post.header("Authorization", "Bearer " + apiToken);
                    }

                    return send(post.build(), UploadResult::from);
                });
    }

    /**
     * What this instance accepts, fetched once and kept.
     *
     * <p>Cached for the life of the process rather than on a timer: these values
     * change a few times a year at most, and the mod re-reads them every time
     * the server restarts anyway. Use {@link #cachedLimits()} from the game
     * thread — the point of caching is that checking a log's size before
     * uploading it does not need a network call.
     */
    public CompletableFuture<Limits> limits() {
        CompletableFuture<Limits> cached = limitsCache.get();

        if (cached != null) {
            // A copy, so a caller cancelling its own future cannot poison the
            // cached one for everybody else.
            return cached.copy();
        }

        CompletableFuture<Limits> fresh =
                send(request(uri("/logs/limits")).GET().build(), Limits::from);

        if (!limitsCache.compareAndSet(null, fresh)) {
            return limitsCache.get().copy();
        }

        fresh.whenComplete((limits, failure) -> {
            if (failure != null) {
                limitsCache.compareAndSet(fresh, null);
            }
        });

        return fresh.copy();
    }

    /** The cached limits if {@link #limits()} has already succeeded. Never blocks. */
    public Optional<Limits> cachedLimits() {
        CompletableFuture<Limits> cached = limitsCache.get();

        return cached != null && cached.isDone() && !cached.isCompletedExceptionally()
                ? Optional.ofNullable(cached.getNow(null))
                : Optional.empty();
    }

    /**
     * The findings for a log that was already uploaded, in the caller's language.
     *
     * <p>Fetched rather than remembered: the diagnosis belongs to the site, and
     * the site's rules improve over time. Asking again means a log uploaded
     * last month can be re-read with today's detectors — and it costs one small
     * request, against storing a copy that starts going stale immediately.
     */
    public CompletableFuture<List<Insight>> insights(String id, String language) {
        if (id == null || !LOG_ID.matcher(id).matches()) {
            throw new IllegalArgumentException("Not a log id: '" + id + "'.");
        }

        String path = "/logs/" + id + "/insights";
        String effective = language == null ? "" : language.trim();

        if (!effective.isEmpty()) {
            path += "?lang=" + URLEncoder.encode(effective, StandardCharsets.UTF_8);
        }

        return send(request(uri(path)).GET().build(),
                json -> json.has("insights") && json.get("insights").isJsonArray()
                        ? Insight.listFrom(json.getAsJsonArray("insights"))
                        : List.of());
    }

    /**
     * Delete a log, using the token that came back when it was uploaded.
     *
     * <p>That token is shown once and stored only as a hash, so this works only
     * for logs the mod wrote down. {@link ApiErrorCode#NOT_FOUND} means it is
     * already gone — which for a delete is arguably the desired state, and worth
     * treating as such rather than as a failure.
     *
     * @throws IllegalArgumentException if {@code id} is not a plausible log id.
     */
    public CompletableFuture<Void> delete(String id, String ownerToken) {
        if (id == null || !LOG_ID.matcher(id).matches()) {
            throw new IllegalArgumentException("Not a log id: '" + id + "'.");
        }

        HttpRequest request = request(uri("/logs/" + id))
                .header("Authorization", "Bearer " + headerSafe(ownerToken, ""))
                .DELETE()
                .build();

        return send(request, json -> null);
    }

    /**
     * Stop accepting work and let go of the threads.
     *
     * <p>Bounded rather than "wait for everything": this runs while a Minecraft
     * server is shutting down, and a hung upload must not be the reason the
     * process refuses to exit.
     */
    @Override
    public void close() {
        http.shutdown();

        try {
            if (!http.awaitTermination(SHUTDOWN_GRACE)) {
                http.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            http.shutdownNow();
        }

        executor.shutdownNow();
    }

    // ---- HTTP plumbing ----

    private HttpRequest.Builder request(URI uri) {
        return HttpRequest.newBuilder(uri)
                .timeout(requestTimeout)
                .header("User-Agent", userAgent)
                .header("Accept", "application/json")
                .header("Accept-Language", language);
    }

    /**
     * The language goes in the query string, not the body.
     *
     * <p>The published schema lists {@code lang} as a body property, but the
     * controller reads it with Laravel's {@code query()}, which only looks at the
     * query string. Sending it in the body silently yields English findings —
     * silently being the problem, since the upload succeeds either way.
     */
    private URI uploadUri(String language) {
        return language.isEmpty()
                ? uri("/logs")
                : uri("/logs?lang=" + URLEncoder.encode(language, StandardCharsets.UTF_8));
    }

    private URI uri(String path) {
        return URI.create(baseUrl + path);
    }

    /**
     * The largest response we are willing to hold in memory.
     *
     * <p>Every response this client reads is small — an upload result, a limits
     * object, an error. But `apiBaseUrl` is configurable, so "the server" can be
     * a typo, a captive portal or a proxy handing back something else entirely,
     * and an unbounded read would let any of them take down the game's JVM. One
     * megabyte is orders of magnitude above the real answers and far below
     * anything that hurts.
     */
    private static final int MAX_RESPONSE_BYTES = 1 << 20;

    private static HttpResponse.BodyHandler<String> boundedString() {
        return info -> HttpResponse.BodySubscribers.limiting(
                HttpResponse.BodySubscribers.ofString(StandardCharsets.UTF_8), MAX_RESPONSE_BYTES);
    }

    private <T> CompletableFuture<T> send(HttpRequest request, Function<JsonObject, T> mapper) {
        return http.sendAsync(request, boundedString())
                .handle((response, failure) -> {
                    if (failure != null) {
                        Throwable cause = unwrap(failure);

                        throw cause instanceof ApiException api
                                ? api
                                : new ApiException(transportError(cause), cause);
                    }

                    return mapper.apply(body(response));
                });
    }

    /**
     * The JSON of a successful response, or an {@link ApiException} describing
     * why there is none.
     */
    private static JsonObject body(HttpResponse<String> response) {
        int status = response.statusCode();
        JsonObject json = Json.parseObject(response.body());

        if (status >= 200 && status < 300) {
            if (json == null) {
                // A 200 that is not JSON is a proxy or a captive portal, not the
                // API. Saying so beats a NullPointerException three frames later.
                throw new ApiException(new ApiError(
                        status,
                        ApiErrorCode.MALFORMED_RESPONSE,
                        "",
                        "The server answered HTTP " + status + " with something that is not JSON.",
                        Optional.empty()));
            }

            // The contract puts failures on non-2xx statuses, so this should be
            // unreachable — but a body that says it failed is believed over a
            // status that says it did not.
            if (json.has("success") && !Json.boolValue(json, "success", true)) {
                throw new ApiException(error(response, json));
            }

            return json;
        }

        throw new ApiException(error(response, json));
    }

    /** An error response, whether or not it was the JSON we expect. */
    private static ApiError error(HttpResponse<String> response, JsonObject json) {
        int status = response.statusCode();
        String rawCode = json == null ? "" : Json.string(json, "error_code");
        String message = json == null ? "" : Json.string(json, "error");

        // An error can arrive from in front of the application — nginx refusing
        // an oversized body, Cloudflare rate limiting, a gateway timeout — and
        // those answer with HTML. The status is then all we have, and it is
        // enough to reach the right case.
        ApiErrorCode code = rawCode.isEmpty() ? codeForStatus(status) : ApiErrorCode.of(rawCode);

        return new ApiError(
                status,
                code,
                rawCode,
                message.isEmpty() ? fallbackMessage(status) : message,
                retryAfter(response));
    }

    private static ApiErrorCode codeForStatus(int status) {
        return switch (status) {
            case 401 -> ApiErrorCode.INVALID_TOKEN;
            case 403 -> ApiErrorCode.INSUFFICIENT_SCOPE;
            case 404 -> ApiErrorCode.NOT_FOUND;
            case 410 -> ApiErrorCode.REMOVED;
            case 413 -> ApiErrorCode.TOO_LARGE;
            case 415 -> ApiErrorCode.UNSUPPORTED_ENCODING;
            case 429 -> ApiErrorCode.RATE_LIMITED;
            default -> status >= 500 ? ApiErrorCode.SERVER_ERROR : ApiErrorCode.UNKNOWN;
        };
    }

    private static String fallbackMessage(int status) {
        if (status >= 300 && status < 400) {
            return "The server redirected the request instead of answering it (HTTP " + status
                    + "). Check the API base URL — an http:// address for a site that serves https:// does this.";
        }

        return "The server answered HTTP " + status + " without a readable error message.";
    }

    /**
     * How long the site wants us to wait.
     *
     * <p>Only the delta-seconds form is read. The HTTP-date form is legal but
     * neither Laravel's throttler nor anything in front of it sends one, and
     * guessing wrong about a clock skew would be worse than saying "later".
     */
    private static Optional<Duration> retryAfter(HttpResponse<?> response) {
        return response.headers().firstValue("Retry-After")
                .map(String::trim)
                .flatMap(value -> {
                    try {
                        long seconds = Long.parseLong(value);

                        return seconds >= 0 ? Optional.of(Duration.ofSeconds(seconds)) : Optional.empty();
                    } catch (NumberFormatException e) {
                        return Optional.empty();
                    }
                });
    }

    /**
     * A failure that is not the site's answer but the absence of one.
     *
     * <p>Kept apart from HTTP errors because the advice differs completely: "no
     * route to alacraft.day" is the player's network, "429" is ours, and telling
     * someone to check their firewall because we were rate limited wastes their
     * evening.
     */
    private static ApiError transportError(Throwable cause) {
        return switch (cause) {
            // Subclass of HttpTimeoutException, so it has to come first. Running
            // out of time while still trying to connect is a reachability
            // problem, not a slow server.
            case HttpConnectTimeoutException e ->
                    ApiError.client(ApiErrorCode.OFFLINE, "Timed out connecting: " + describe(e));
            case HttpTimeoutException e ->
                    ApiError.client(ApiErrorCode.TIMEOUT, "The request timed out: " + describe(e));
            case UnknownHostException e ->
                    ApiError.client(ApiErrorCode.OFFLINE, "Host could not be resolved: " + describe(e));
            case ConnectException e ->
                    ApiError.client(ApiErrorCode.OFFLINE, "Connection refused: " + describe(e));
            case SSLException e ->
                    ApiError.client(ApiErrorCode.TLS, "The secure connection failed: " + describe(e));
            case IOException e ->
                    ApiError.client(ApiErrorCode.OFFLINE, "The connection failed: " + describe(e));
            case null -> ApiError.client(ApiErrorCode.INTERNAL, "The request failed for an unknown reason.");
            default -> ApiError.client(ApiErrorCode.INTERNAL, describe(cause));
        };
    }

    private static String describe(Throwable throwable) {
        String message = throwable.getMessage();

        return message == null || message.isBlank() ? throwable.getClass().getSimpleName() : message;
    }

    private static Throwable unwrap(Throwable throwable) {
        Throwable current = throwable;

        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }

        return current;
    }

    /** JSON to the bytes that go on the wire, gzipped unless that was turned off. */
    private byte[] encode(JsonObject json) {
        byte[] raw = json.toString().getBytes(StandardCharsets.UTF_8);

        if (!compress) {
            return raw;
        }

        // Everything here is in memory, so an IOException means something far
        // worse than a failed upload — but it must still arrive as an
        // ApiException like every other failure, not as a raw IOException the
        // caller has no branch for.
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, raw.length / 8));

            try (GZIPOutputStream gzip = new GZIPOutputStream(out)) {
                gzip.write(raw);
            }

            return out.toByteArray();
        } catch (IOException e) {
            throw new ApiException(
                    ApiError.client(ApiErrorCode.INTERNAL, "Could not compress the log: " + describe(e)), e);
        }
    }

    /**
     * A header value with anything unsendable replaced.
     *
     * <p>{@link HttpRequest.Builder#header} throws on characters outside
     * printable ASCII, which would turn an odd mod version or a locale from a
     * hand-edited config into a failed upload with a baffling message.
     */
    private static String headerSafe(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }

        StringBuilder safe = new StringBuilder(value.length());

        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            safe.append(c >= 0x20 && c <= 0x7e ? c : '.');
        }

        return safe.toString();
    }

    /** Configures a client. Everything but the base URL has a working default. */
    public static final class Builder {

        private final String baseUrl;
        private String userAgent = AlaLoggerApi.userAgent("dev", "unknown", "unknown");
        private String source = "";
        private String apiToken = "";
        private String language = "en";
        private Duration connectTimeout = DEFAULT_CONNECT_TIMEOUT;
        private Duration requestTimeout = DEFAULT_REQUEST_TIMEOUT;
        private boolean compress = true;

        private Builder(String baseUrl) {
            if (baseUrl == null || baseUrl.isBlank()) {
                throw new IllegalArgumentException("The API base URL is required.");
            }

            String trimmed = baseUrl.trim();

            while (trimmed.endsWith("/")) {
                trimmed = trimmed.substring(0, trimmed.length() - 1);
            }

            // Fail here rather than at the first upload: a typo in a config file
            // should be reported when the file is read, not the next time
            // something crashes.
            URI parsed;

            try {
                parsed = new URI(trimmed);
            } catch (Exception e) {
                throw new IllegalArgumentException("The API base URL is not a URL: '" + baseUrl + "'.", e);
            }

            String scheme = parsed.getScheme() == null ? "" : parsed.getScheme().toLowerCase(Locale.ROOT);

            if (!scheme.equals("http") && !scheme.equals("https") || parsed.getHost() == null) {
                throw new IllegalArgumentException(
                        "The API base URL must be http:// or https:// with a host: '" + baseUrl + "'.");
            }

            this.baseUrl = trimmed;
        }

        /** See {@link AlaLoggerApi#userAgent(String, String, String)} for the expected shape. */
        public Builder userAgent(String userAgent) {
            this.userAgent = headerSafe(userAgent, this.userAgent);

            return this;
        }

        /** Default {@code source} for uploads, as {@code name/version}. */
        public Builder source(String source) {
            this.source = source == null ? "" : source.trim();

            return this;
        }

        /** Personal API token from /profile; empty uploads anonymously. */
        public Builder apiToken(String apiToken) {
            this.apiToken = headerSafe(apiToken, "").trim();

            return this;
        }

        /** Default language for findings: en, ru, uk, de, fr, es or ja. */
        public Builder language(String language) {
            this.language = headerSafe(language, this.language).trim();

            return this;
        }

        public Builder connectTimeout(Duration connectTimeout) {
            this.connectTimeout = require(connectTimeout, "connectTimeout");

            return this;
        }

        public Builder requestTimeout(Duration requestTimeout) {
            this.requestTimeout = require(requestTimeout, "requestTimeout");

            return this;
        }

        /**
         * Turn off gzip for uploads.
         *
         * <p>Here because the base URL is configurable: an instance that predates
         * compressed uploads answers {@link ApiErrorCode#UNSUPPORTED_ENCODING},
         * and without this switch that would be a dead end rather than a setting
         * to change. Leave it on for alacraft.day.
         */
        public Builder compress(boolean compress) {
            this.compress = compress;

            return this;
        }

        public AlaLoggerApi build() {
            warnIfTokenTravelsInClear();

            return new AlaLoggerApi(this);
        }

        /**
         * Say so when an account token is about to be sent over plain HTTP.
         *
         * <p>http:// stays allowed — local development against a Log Checker on
         * 127.0.0.1 is a normal thing to do, and our own docs suggest it. What is
         * not normal is copying that line into a production config with a real
         * token in it, which is exactly how the docs' example becomes a leak.
         * Loopback is exempt because nothing leaves the machine there.
         */
        private void warnIfTokenTravelsInClear() {
            if (apiToken.isEmpty()) {
                return;
            }

            URI parsed;
            try {
                parsed = URI.create(baseUrl);
            } catch (IllegalArgumentException e) {
                return; // build() is about to fail on this anyway.
            }

            String scheme = parsed.getScheme() == null ? "" : parsed.getScheme().toLowerCase(Locale.ROOT);
            String host = parsed.getHost() == null ? "" : parsed.getHost().toLowerCase(Locale.ROOT);

            boolean loopback = host.equals("localhost") || host.equals("127.0.0.1") || host.equals("::1");

            if (scheme.equals("http") && !loopback) {
                day.alacraft.alalogger.AlaLogger.LOGGER.warn(
                        "apiBaseUrl uses http://, so the API token in the config will be sent unencrypted "
                                + "to {}. Use https:// unless this is a local instance.", host);
            }
        }

        private static Duration require(Duration value, String name) {
            if (value == null || value.isNegative() || value.isZero()) {
                throw new IllegalArgumentException(name + " must be a positive duration.");
            }

            return value;
        }
    }
}
