package day.alacraft.alalogger.api;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The client against a throwaway HTTP server on loopback.
 *
 * <p>Never against alacraft.day: these tests have to run offline, in CI, and on
 * every build, and a test suite that uploads to production is one that stops
 * being run. The fixtures below are copied from real responses, so what is
 * asserted is still the real contract.
 */
class AlaLoggerApiTest {

    /**
     * A 201 as the site actually sends it, demo log AlaDemo1 with the shape kept.
     *
     * <p>The {@code url} key on each solution is not part of the contract and
     * {@link Insight.Solution} has no such field. It stays here as the one place
     * that proves an unknown key — present or JSON null — does not break the
     * parse; a stricter parser would reject the whole insight over it.
     */
    private static final String UPLOAD_RESPONSE = """
            {
              "success": true,
              "token": "s3cr3t-owner-token",
              "truncated": false,
              "id": "aB3xY9kM",
              "url": "https://alacraft.day/en/logs/aB3xY9kM",
              "raw": "https://alacraft.day/api/v1/logs/aB3xY9kM/raw",
              "source": "alalogger/1.0.0",
              "metadata": [],
              "created": "2026-08-28T12:00:00+00:00",
              "expires": "2026-11-26T12:00:00+00:00",
              "size": 748,
              "lines": 22,
              "errors": 2,
              "detected": {
                "type": "crash_report",
                "loader": "fabric",
                "minecraft_version": "26.2",
                "java_version": "25.0.1"
              },
              "insights": [
                {
                  "code": "out_of_memory",
                  "severity": "error",
                  "message": "The game ran out of memory.",
                  "hint": "Java was only allowed to use 2G.",
                  "line": 7,
                  "count": 3,
                  "solutions": [
                    {"text": "Give Minecraft more memory.", "url": "https://alacraft.day/en/logs"},
                    {"text": "Remove the heaviest mods.", "url": null}
                  ]
                }
              ]
            }
            """;

    private HttpServer server;
    private ExecutorService serverThreads;
    private String baseUrl;

    private final List<Recorded> requests = Collections.synchronizedList(new ArrayList<>());
    private final List<AlaLoggerApi> clients = Collections.synchronizedList(new ArrayList<>());
    private volatile Function<Recorded, Reply> responder = request -> Reply.json(200, "{\"success\":true}");

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        // Daemon threads: one test parks a handler on purpose to force a timeout,
        // and a live non-daemon thread would keep the test JVM from exiting.
        serverThreads = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "test-http");
            thread.setDaemon(true);
            return thread;
        });
        server.setExecutor(serverThreads);
        server.createContext("/", this::handle);
        server.start();

        baseUrl = "http://" + InetAddress.getLoopbackAddress().getHostAddress()
                + ":" + server.getAddress().getPort() + "/api/v1";
    }

    @AfterEach
    void stopServer() {
        clients.forEach(AlaLoggerApi::close);
        clients.clear();
        server.stop(0);
        serverThreads.shutdownNow();
    }

    // ---- Uploading ----

    @Test
    void uploadReturnsTheLinkAndWhatTheSiteFound() throws Exception {
        responder = request -> Reply.json(201, UPLOAD_RESPONSE);

        UploadResult result = client().upload("java.lang.OutOfMemoryError").get(10, TimeUnit.SECONDS);

        assertEquals("aB3xY9kM", result.id());
        assertEquals("https://alacraft.day/en/logs/aB3xY9kM", result.url());
        assertEquals("https://alacraft.day/api/v1/logs/aB3xY9kM/raw", result.raw());
        assertEquals("s3cr3t-owner-token", result.token());
        assertTrue(result.isDeletable());
        assertFalse(result.truncated());
        assertEquals(748L, result.size());
        assertEquals(22, result.lines());
        assertEquals(2, result.errors());

        assertEquals("crash_report", result.detected().type().orElseThrow());
        assertEquals("fabric", result.detected().loader().orElseThrow());
        assertEquals("26.2", result.detected().minecraftVersion().orElseThrow());
        assertEquals("25.0.1", result.detected().javaVersion().orElseThrow());

        assertEquals(1, result.insights().size());
        Insight insight = result.insights().get(0);
        assertEquals("out_of_memory", insight.code());
        assertTrue(insight.isError());
        assertEquals("The game ran out of memory.", insight.message());
        assertEquals("Java was only allowed to use 2G.", insight.hint());
        assertEquals(7, insight.line().orElseThrow());
        assertEquals(3, insight.count());
        assertEquals(2, insight.solutions().size());
        assertEquals("Give Minecraft more memory.", insight.solutions().get(0).text());
        assertEquals("Remove the heaviest mods.", insight.solutions().get(1).text());
    }

    @Test
    void uploadIsCompressedAndTheServerCanReadItBack() throws Exception {
        responder = request -> Reply.json(201, UPLOAD_RESPONSE);

        // Repetitive like a real log, so the ratio is the one that matters.
        String log = "[12:00:00] [Server thread/INFO]: Loading mod alaindustrial\n".repeat(2000);

        client().upload(log).get(10, TimeUnit.SECONDS);

        Recorded request = only();
        assertEquals("gzip", request.header("Content-Encoding"));
        assertEquals("application/json", request.header("Content-Type"));

        // The point of the header is that the other side can undo it: `body` was
        // produced by running the received bytes through GZIPInputStream.
        assertEquals(log, request.json().get("content").getAsString());
        assertTrue(request.rawBytes().length * 5 < request.body().length(),
                "gzip should shrink a log by far more than 5x, got "
                        + request.rawBytes().length + " from " + request.body().length());
    }

    @Test
    void uploadWithoutCompressionSendsPlainJson() throws Exception {
        responder = request -> Reply.json(201, UPLOAD_RESPONSE);

        client(builder -> builder.compress(false)).upload("hello").get(10, TimeUnit.SECONDS);

        Recorded request = only();
        assertNull(request.header("Content-Encoding"));
        assertEquals("hello", request.json().get("content").getAsString());
    }

    @Test
    void uploadIdentifiesItselfTheWayTheApiAsksFor() throws Exception {
        responder = request -> Reply.json(201, UPLOAD_RESPONSE);

        AlaLoggerApi api = client(builder -> builder
                .userAgent(AlaLoggerApi.userAgent("1.0.0", "26.2", "fabric"))
                .source("alalogger/1.0.0")
                .apiToken("account-token")
                .language("ru"));

        api.upload("a log").get(10, TimeUnit.SECONDS);

        Recorded request = only();
        assertEquals("POST", request.method);
        assertEquals("/api/v1/logs", request.uri.getPath());
        assertEquals("AlaLogger/1.0.0 (Minecraft 26.2; fabric)", request.header("User-Agent"));
        assertEquals("application/json", request.header("Accept"));
        assertEquals("ru", request.header("Accept-Language"));
        assertEquals("Bearer account-token", request.header("Authorization"));
        assertEquals("alalogger/1.0.0", request.json().get("source").getAsString());

        // The site reads the language from the query string, not the body.
        assertEquals("lang=ru", request.uri.getQuery());
        assertFalse(request.json().has("lang"));
    }

    @Test
    void anonymousUploadSendsNoToken() throws Exception {
        responder = request -> Reply.json(201, UPLOAD_RESPONSE);

        client().upload("a log").get(10, TimeUnit.SECONDS);

        assertNull(only().header("Authorization"));
    }

    @Test
    void metadataTravelsInTheAgreedShape() throws Exception {
        responder = request -> Reply.json(201, UPLOAD_RESPONSE);

        UploadRequest upload = new UploadRequest("a log", "alalogger/1.0.0", "de", List.of(
                MetadataEntry.of("server", "survival-01"),
                new MetadataEntry("mods", "148", "Mods loaded", false)));

        client().upload(upload).get(10, TimeUnit.SECONDS);

        var metadata = only().json().getAsJsonArray("metadata");
        assertEquals(2, metadata.size());
        assertEquals("server", metadata.get(0).getAsJsonObject().get("key").getAsString());
        assertEquals("survival-01", metadata.get(0).getAsJsonObject().get("value").getAsString());
        assertFalse(metadata.get(0).getAsJsonObject().has("visible"));
        assertEquals("Mods loaded", metadata.get(1).getAsJsonObject().get("label").getAsString());
        assertFalse(metadata.get(1).getAsJsonObject().get("visible").getAsBoolean());
        assertEquals("lang=de", only().uri.getQuery());
    }

    // ---- Failures the caller has to tell apart ----

    @Test
    void rateLimitingSaysHowLongToWait() {
        responder = request -> Reply.json(429, """
                {"success": false, "error": "Rate limit exceeded.", "error_code": "rate_limited"}
                """).header("Retry-After", "42");

        ApiError error = failure(client().upload("a log"));

        assertEquals(ApiErrorCode.RATE_LIMITED, error.code());
        assertEquals(429, error.status());
        assertEquals(Duration.ofSeconds(42), error.retryAfter().orElseThrow());
        assertEquals(42L, error.retryAfterSeconds(0));
        assertTrue(error.isRetryable());
        assertFalse(error.isTransport());
    }

    @Test
    void anHtmlErrorPageFromTheProxyStillLandsOnTheRightCase() {
        // nginx refusing an oversized body never reaches the application, so
        // there is no JSON and no error_code — only the status.
        responder = request -> Reply.html(413, "<html><head><title>413 Request Entity Too Large</title></head></html>");

        ApiError error = failure(client().upload("a log"));

        assertEquals(ApiErrorCode.TOO_LARGE, error.code());
        assertEquals(413, error.status());
        assertEquals("", error.rawCode());
        assertFalse(error.message().isBlank(), "a fallback sentence is expected");
        assertFalse(error.isRetryable());
    }

    @Test
    void anUnknownErrorCodeIsKeptRatherThanLost() {
        responder = request -> Reply.json(422, """
                {"success": false, "error": "Something new.", "error_code": "brand_new_code"}
                """);

        ApiError error = failure(client().upload("a log"));

        assertEquals(ApiErrorCode.UNKNOWN, error.code());
        assertEquals("brand_new_code", error.rawCode());
        assertEquals("Something new.", error.message());
    }

    @Test
    void knownErrorCodesAreTyped() {
        responder = request -> Reply.json(422, """
                {"success": false, "error": "content must be a non-empty string.", "error_code": "invalid_content"}
                """);

        assertEquals(ApiErrorCode.INVALID_CONTENT, failure(client().upload("a log")).code());

        responder = request -> Reply.json(415, """
                {"success": false, "error": "Unsupported Content-Encoding.", "error_code": "unsupported_encoding"}
                """);

        assertEquals(ApiErrorCode.UNSUPPORTED_ENCODING, failure(client().upload("a log")).code());
    }

    @Test
    void aSuccessThatIsNotJsonIsReportedAsSuch() {
        // A captive portal or a misrouted proxy: HTTP 200, and not a word of JSON.
        responder = request -> Reply.html(200, "<html>Sign in to continue</html>");

        ApiError error = failure(client().upload("a log"));

        assertEquals(ApiErrorCode.MALFORMED_RESPONSE, error.code());
        assertEquals(200, error.status());
    }

    @Test
    void aRedirectSaysToCheckTheUrl() {
        responder = request -> Reply.html(301, "").header("Location", "https://alacraft.day/api/v1/logs");

        ApiError error = failure(client().upload("a log"));

        assertEquals(301, error.status());
        assertTrue(error.message().contains("base URL"), "expected advice about the URL, got: " + error.message());
    }

    @Test
    void aServerErrorIsWorthRetrying() {
        responder = request -> Reply.html(502, "<html>502 Bad Gateway</html>");

        ApiError error = failure(client().upload("a log"));

        assertEquals(ApiErrorCode.SERVER_ERROR, error.code());
        assertTrue(error.isRetryable());
    }

    @Test
    void aTimeoutIsNotAServerFailure() {
        responder = request -> {
            sleep(Duration.ofSeconds(2));
            return Reply.json(201, UPLOAD_RESPONSE);
        };

        ApiError error = failure(
                client(builder -> builder.requestTimeout(Duration.ofMillis(300))).upload("a log"));

        assertEquals(ApiErrorCode.TIMEOUT, error.code());
        assertTrue(error.isTransport());
        assertEquals(0, error.status(), "there was no HTTP response to have a status");
        assertTrue(error.isRetryable());
    }

    @Test
    void anUnreachableSiteIsNotAnApiError() throws IOException {
        int deadPort;

        try (ServerSocket socket = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
            deadPort = socket.getLocalPort();
        }

        AlaLoggerApi api = register(AlaLoggerApi
                .builder("http://" + InetAddress.getLoopbackAddress().getHostAddress() + ":" + deadPort + "/api/v1")
                .build());

        ApiError error = failure(api.upload("a log"));

        assertEquals(ApiErrorCode.OFFLINE, error.code());
        assertTrue(error.isTransport());
        assertEquals(0, error.status());
    }

    /**
     * The "could not reach ..." message names the configured host.
     *
     * <p>This was a constant returning "alacraft.day" for everyone, so an
     * operator running their own instance was told our site was down when
     * theirs was — and went to check a machine that was not theirs.
     */
    @Test
    void theClientNamesTheHostItActuallyTalksTo() {
        assertEquals("alacraft.day",
                register(AlaLoggerApi.builder("https://alacraft.day/api/v1").build()).host());

        assertEquals("logs.example.org",
                register(AlaLoggerApi.builder("https://logs.example.org/api/v1").build()).host());

        // A port is not part of the host, and a message reading "could not reach
        // 127.0.0.1:8123" is still the right thing to show.
        assertEquals("127.0.0.1",
                register(AlaLoggerApi.builder("http://127.0.0.1:8123/api/v1").build()).host());
    }

    // ---- Limits ----

    @Test
    void limitsAreParsedAndThenRemembered() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        responder = request -> {
            calls.incrementAndGet();
            return Reply.json(200, """
                    {"success": true, "storageTime": 7776000, "maxLength": 10485760, "maxLines": 25000}
                    """);
        };

        AlaLoggerApi api = client();
        assertTrue(api.cachedLimits().isEmpty(), "nothing is known before the first call");

        Limits limits = api.limits().get(10, TimeUnit.SECONDS);

        assertEquals(10L * 1024 * 1024, limits.maxLength());
        assertEquals(25_000, limits.maxLines());
        assertEquals(Duration.ofDays(90), limits.storageTime());
        assertEquals(90, limits.storageDays());
        assertEquals("/api/v1/logs/limits", only().uri.getPath());

        assertEquals(limits, api.limits().get(10, TimeUnit.SECONDS));
        assertEquals(limits, api.cachedLimits().orElseThrow());
        assertEquals(1, calls.get(), "the second call must not reach the network");
    }

    @Test
    void aFailedLimitsLookupIsNotRemembered() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        responder = request -> calls.incrementAndGet() == 1
                ? Reply.html(503, "<html>503</html>")
                : Reply.json(200, """
                        {"success": true, "storageTime": 60, "maxLength": 10, "maxLines": 1}
                        """);

        AlaLoggerApi api = client();

        assertEquals(ApiErrorCode.SERVER_ERROR, failure(api.limits()).code());
        assertTrue(api.cachedLimits().isEmpty());

        assertEquals(10L, api.limits().get(10, TimeUnit.SECONDS).maxLength());
        assertEquals(2, calls.get());
    }

    // ---- Deleting ----

    @Test
    void deleteUsesTheLogsOwnToken() throws Exception {
        responder = request -> Reply.json(200, "{\"success\": true}");

        client(builder -> builder.apiToken("account-token"))
                .delete("aB3xY9kM", "owner-token").get(10, TimeUnit.SECONDS);

        Recorded request = only();
        assertEquals("DELETE", request.method);
        assertEquals("/api/v1/logs/aB3xY9kM", request.uri.getPath());
        // The account token authorises uploads; only the log's own token deletes it.
        assertEquals("Bearer owner-token", request.header("Authorization"));
    }

    @Test
    void deleteWithTheWrongTokenIsTold() {
        responder = request -> Reply.json(401, """
                {"success": false, "error": "Invalid or missing token.", "error_code": "invalid_token"}
                """);

        ApiError error = failure(client().delete("aB3xY9kM", "wrong"));

        assertEquals(ApiErrorCode.INVALID_TOKEN, error.code());
        assertFalse(error.isRetryable(), "resending a rejected token only burns the rate limit");
    }

    @Test
    void deleteRefusesAnIdThatIsNotOne() {
        AlaLoggerApi api = client();

        // Concatenated into a URL, so this is the guard against a path escaping
        // the endpoint — and it fails at the call site, not in a future.
        assertThrows(IllegalArgumentException.class, () -> api.delete("../../admin", "token"));
        assertThrows(IllegalArgumentException.class, () -> api.delete("", "token"));
    }

    // ---- Shapes we do not control ----

    @Test
    void aResponseMissingHalfItsFieldsStillParses() throws Exception {
        // Nothing here is guaranteed by us; a future field, a null, or an
        // insight without a line must not turn a stored log into a crash.
        responder = request -> Reply.json(201, """
                {
                  "success": true,
                  "id": "aB3xY9kM",
                  "url": "https://alacraft.day/en/logs/aB3xY9kM",
                  "truncated": true,
                  "detected": {"type": null, "loader": "paper"},
                  "insights": [
                    {"code": "eula_not_accepted", "severity": "error", "message": "Accept the EULA.", "line": null},
                    "not an object"
                  ],
                  "something_new": {"nested": true}
                }
                """);

        UploadResult result = client().upload("a log").get(10, TimeUnit.SECONDS);

        assertTrue(result.truncated());
        assertEquals("", result.token());
        assertFalse(result.isDeletable());
        assertEquals(0L, result.size());
        assertTrue(result.detected().type().isEmpty());
        assertEquals("paper", result.detected().loader().orElseThrow());
        assertTrue(result.detected().javaVersion().isEmpty());

        assertEquals(1, result.insights().size(), "the entry that is not an object is skipped");
        Insight insight = result.insights().get(0);
        assertTrue(insight.line().isEmpty());
        assertEquals(1, insight.count(), "a missing count still means it happened once");
        assertEquals("", insight.hint());
        assertTrue(insight.solutions().isEmpty());
    }

    @Test
    void userAgentFollowsTheDocumentedShape() {
        assertEquals("AlaLogger/1.0.0 (Minecraft 26.2; fabric)",
                AlaLoggerApi.userAgent("1.0.0", "26.2", "fabric"));

        // Blanks become the documented placeholders rather than a broken header.
        assertEquals("AlaLogger/dev (Minecraft unknown; neoforge)",
                AlaLoggerApi.userAgent("", null, "neoforge"));

        // A header cannot carry a newline; replacing beats failing the upload.
        assertEquals("AlaLogger/1.0. (Minecraft 26.2; fabric)",
                AlaLoggerApi.userAgent("1.0\n", "26.2", "fabric"));
    }

    @Test
    void aBaseUrlThatCannotWorkIsRejectedImmediately() {
        assertThrows(IllegalArgumentException.class, () -> AlaLoggerApi.builder(""));
        assertThrows(IllegalArgumentException.class, () -> AlaLoggerApi.builder("alacraft.day/api/v1"));
        assertThrows(IllegalArgumentException.class, () -> AlaLoggerApi.builder("ftp://alacraft.day/api"));
        assertThrows(IllegalArgumentException.class,
                () -> AlaLoggerApi.builder("https://alacraft.day").requestTimeout(Duration.ZERO));

        // A trailing slash is a config-file typo, not an error.
        register(AlaLoggerApi.builder("https://alacraft.day/api/v1//").build());
    }

    // ---- Harness ----

    private AlaLoggerApi client() {
        return client(builder -> builder);
    }

    private AlaLoggerApi client(Function<AlaLoggerApi.Builder, AlaLoggerApi.Builder> customise) {
        return register(customise.apply(AlaLoggerApi.builder(baseUrl)).build());
    }

    private AlaLoggerApi register(AlaLoggerApi api) {
        clients.add(api);

        return api;
    }

    /** The single request the test expected the client to make. */
    private Recorded only() {
        assertEquals(1, requests.size(), "expected exactly one request, got " + requests.size());

        return requests.get(0);
    }

    /** The {@link ApiError} a call failed with, unwrapped from the future. */
    private static ApiError failure(CompletableFuture<?> future) {
        ExecutionException thrown = assertThrows(ExecutionException.class,
                () -> future.get(10, TimeUnit.SECONDS));

        return ApiException.of(thrown).error();
    }

    private void handle(HttpExchange exchange) throws IOException {
        byte[] raw = exchange.getRequestBody().readAllBytes();
        boolean gzipped = "gzip".equals(exchange.getRequestHeaders().getFirst("Content-Encoding"));
        byte[] decoded = gzipped ? gunzip(raw) : raw;

        Map<String, String> headers = new LinkedHashMap<>();
        exchange.getRequestHeaders().forEach((name, values) -> headers.put(
                name.toLowerCase(java.util.Locale.ROOT), String.join(", ", values)));

        Recorded recorded = new Recorded(
                exchange.getRequestMethod(),
                exchange.getRequestURI(),
                headers,
                raw,
                new String(decoded, StandardCharsets.UTF_8));

        requests.add(recorded);

        Reply reply = responder.apply(recorded);
        byte[] body = reply.body.getBytes(StandardCharsets.UTF_8);

        exchange.getResponseHeaders().set("Content-Type", reply.contentType);
        reply.headers.forEach((name, value) -> exchange.getResponseHeaders().set(name, value));
        exchange.sendResponseHeaders(reply.status, body.length == 0 ? -1 : body.length);

        try (exchange) {
            exchange.getResponseBody().write(body);
        }
    }

    private static byte[] gunzip(byte[] raw) throws IOException {
        try (GZIPInputStream in = new GZIPInputStream(new java.io.ByteArrayInputStream(raw))) {
            return in.readAllBytes();
        }
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** What the server received. {@code body} is already decompressed. */
    private record Recorded(String method, URI uri, Map<String, String> headers, byte[] rawBytes, String body) {

        String header(String name) {
            return headers.get(name.toLowerCase(java.util.Locale.ROOT));
        }

        JsonObject json() {
            try {
                return JsonParser.parseString(body).getAsJsonObject();
            } catch (RuntimeException e) {
                throw new UncheckedIOException(new IOException("Not JSON: " + body, e));
            }
        }
    }

    /** What the server should answer with. */
    private record Reply(int status, String contentType, String body, Map<String, String> headers) {

        static Reply json(int status, String body) {
            return new Reply(status, "application/json", body, new LinkedHashMap<>());
        }

        static Reply html(int status, String body) {
            return new Reply(status, "text/html", body, new LinkedHashMap<>());
        }

        Reply header(String name, String value) {
            headers.put(name, value);

            return this;
        }
    }
}
