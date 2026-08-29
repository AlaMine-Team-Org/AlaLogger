package day.alacraft.alalogger;

import day.alacraft.alalogger.api.AlaLoggerApi;
import day.alacraft.alalogger.api.ApiException;
import day.alacraft.alalogger.api.Limits;
import day.alacraft.alalogger.api.UploadRequest;
import day.alacraft.alalogger.api.UploadResult;
import day.alacraft.alalogger.history.UploadHistory;
import day.alacraft.alalogger.history.UploadRecord;
import day.alacraft.alalogger.i18n.Messages;
import day.alacraft.alalogger.logs.LogContent;
import day.alacraft.alalogger.logs.LogFile;
import day.alacraft.alalogger.logs.LogFiles;
import day.alacraft.alalogger.logs.LogReader;
import day.alacraft.alalogger.logs.ReadLimits;
import day.alacraft.alalogger.logs.ReadMode;
import day.alacraft.alalogger.redact.RedactionResult;
import day.alacraft.alalogger.redact.Redactor;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * The whole job, in one place: find a file, read the useful part of it, strip
 * the secrets out, upload it, remember how to delete it again.
 *
 * <p>Deliberately free of Minecraft types even though it is the mod's core.
 * Every platform we will add — NeoForge, Paper, Velocity — needs exactly this
 * sequence and differs only in how it prints the answer, so the sequence lives
 * where all of them can share it and where it can be tested without a game.
 */
public final class UploadService {

    /**
     * Where reading and cleaning happen.
     *
     * <p>Virtual threads because the work is file IO followed by regex passes,
     * and because a server should not have a platform thread parked for it. The
     * one thing that must never happen is doing this on the game thread — that
     * is what made the first version look unresponsive.
     */
    private static final java.util.concurrent.Executor WORKER =
            java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();

    private final Config config;
    private final AlaLoggerApi api;
    private final LogFiles files;
    private final UploadHistory history;

    public UploadService(Config config, AlaLoggerApi api, LogFiles files, UploadHistory history) {
        this.config = config;
        this.api = api;
        this.files = files;
        this.history = history;
    }

    public LogFiles files() {
        return files;
    }

    public UploadHistory history() {
        return history;
    }

    public Config config() {
        return config;
    }

    /**
     * Read, clean and upload one file.
     *
     * <p>The redaction pass here is the one that matters. The site cleans the
     * text again when it arrives, but by then it has already crossed the
     * network — and for a JVM crash file that text contains a token granting
     * full access to the player's Minecraft account. Cleaning it here means the
     * token never leaves the machine at all, which is a different promise from
     * "we delete it on receipt", and the reason Modrinth tells people never to
     * share these files with services that only do the latter.
     */
    public CompletableFuture<Upload> upload(LogFile file, String language) {
        // Reading and cleaning run OFF the caller's thread, and that is not a
        // detail. Both are real work — a 650 KB log takes long enough that doing
        // it inline froze the server for the duration, which swallowed the
        // "uploading…" line the player was supposed to see first and made the
        // command look like it had done nothing at all. The network call was
        // always async; the part before it was the problem.
        return CompletableFuture
                .supplyAsync(() -> prepare(file, language), WORKER)
                .thenCompose(prepared -> api.upload(prepared.request())
                        .thenApply(result -> {
                            remember(result, file);

                            return new Upload(
                                    result,
                                    prepared.cleaned(),
                                    // Either side may have shortened the log: the
                                    // reader when the file was over the limits, the
                                    // redactor when the cleaned text still was. The
                                    // player is told once, either way.
                                    prepared.truncated() || result.truncated(),
                                    prepared.mode(),
                                    file
                            );
                        }));
    }

    /** Read and clean, on a worker thread. */
    private Prepared prepare(LogFile file, String language) {
        LogContent content;

        try {
            content = LogReader.read(file, limits());
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }

        if (content.isEmpty()) {
            throw new EmptyLogException(file.name());
        }

        ReadLimits limits = limits();

        // The redactor gets the SAME limits the reader used, not its own
        // hardcoded pair. They agree today, which is why nothing is visibly
        // broken — but the whole point of fetching limits over the network is
        // that the site can raise them without a mod release, and on that day a
        // tail-read log would be cut back to its FIRST bytes by a redactor still
        // believing in 10 MiB.
        RedactionResult cleaned = new Redactor(Redactor.defaultRules(),
                (int) Math.min(limits.maxBytes(), Integer.MAX_VALUE), limits.maxLines())
                .redact(content.text());

        // Redaction is not length-neutral: "1.2.3.4" becomes "***.***.***.***".
        // On a log full of addresses the cleaned text can end up over the limit
        // it was just trimmed to, and the server would reject an upload the mod
        // believes is within bounds. Trim again — from the end the file was read
        // from, so the interesting part survives.
        cleaned = enforceLimit(cleaned, limits, content.mode());

        return new Prepared(
                new UploadRequest(
                        cleaned.content(),
                        BuildInfo.sourceTag(),
                        Messages.siteLocale(language),
                        List.of()
                ),
                cleaned,
                content.truncated() || cleaned.truncatedBytes() || cleaned.truncatedLines(),
                content.mode()
        );
    }

    /**
     * Cut the cleaned text back under the limit if redaction pushed it over.
     *
     * <p>Which end is cut follows how the file was read: the tail of a running
     * server's log is where the error is, the head of a crash report is where
     * the cause is. Cutting the wrong end throws away the only part anyone
     * wanted.
     */
    static RedactionResult enforceLimit(RedactionResult cleaned, ReadLimits limits, ReadMode mode) {
        String text = cleaned.content();
        byte[] bytes = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);

        if (bytes.length <= limits.maxBytes()) {
            return cleaned;
        }

        String trimmed = mode == ReadMode.TAIL
                ? keepTail(text, limits.maxBytes())
                : keepHead(text, limits.maxBytes());

        return new RedactionResult(
                trimmed,
                cleaned.summary(),
                true,
                cleaned.truncatedLines(),
                trimmed.isEmpty() ? 0 : (int) trimmed.lines().count());
    }

    /** Whole lines from the end, up to the byte budget. */
    static String keepTail(String text, long maxBytes) {
        String[] lines = text.split("\n", -1);
        StringBuilder out = new StringBuilder();
        long used = 0;

        for (int i = lines.length - 1; i >= 0; i--) {
            long size = lines[i].getBytes(java.nio.charset.StandardCharsets.UTF_8).length + 1L;
            if (used + size > maxBytes) {
                break;
            }
            out.insert(0, lines[i]).insert(0, out.isEmpty() ? "" : "");
            if (i > 0) {
                out.insert(0, '\n');
            }
            used += size;
        }

        return out.toString().strip();
    }

    /** Whole lines from the start, up to the byte budget. */
    static String keepHead(String text, long maxBytes) {
        StringBuilder out = new StringBuilder();
        long used = 0;

        for (String line : text.split("\n", -1)) {
            long size = line.getBytes(java.nio.charset.StandardCharsets.UTF_8).length + 1L;
            if (used + size > maxBytes) {
                break;
            }
            if (!out.isEmpty()) {
                out.append('\n');
            }
            out.append(line);
            used += size;
        }

        return out.toString().strip();
    }

    private record Prepared(UploadRequest request, RedactionResult cleaned, boolean truncated, ReadMode mode) {
    }

    /**
     * Delete a previously uploaded log, using the token this server stored when
     * it uploaded it.
     *
     * <p>The token is why the history file exists. Held only in memory, it would
     * be lost on restart, silently stripping the ability to delete your own log —
     * which is exactly when you want it, because the reason you are restarting is
     * usually the reason you uploaded.
     */
    public CompletableFuture<String> delete(String id) {
        Optional<UploadRecord> record = "last".equalsIgnoreCase(id) ? history.last() : history.findById(id);

        if (record.isEmpty() || !record.get().hasToken()) {
            return CompletableFuture.failedFuture(new UnknownUploadException(id));
        }

        UploadRecord found = record.get();

        return api.delete(found.id(), found.token())
                .thenApply(ignored -> {
                    history.remove(found.id());
                    return found.id();
                });
    }

    /**
     * List the log files, off the game thread.
     *
     * <p>Scanning four directories and stat-ing every file in them is disk work,
     * and disk work on a server whose disk is busy — which is the situation
     * people run this command in — is exactly where a tick gets lost.
     */
    public CompletableFuture<List<LogFile>> list(String filter) {
        return CompletableFuture.supplyAsync(() -> files.list(filter), WORKER);
    }

    /** Resolve one file by name, off the game thread. Empty if it does not exist. */
    public CompletableFuture<Optional<LogFile>> find(String name) {
        return CompletableFuture.supplyAsync(() -> files.find(name), WORKER);
    }

    /** The newest file of any of these types, off the game thread. */
    public CompletableFuture<Optional<LogFile>> latest(day.alacraft.alalogger.logs.LogFileType... types) {
        return CompletableFuture.supplyAsync(
                () -> types.length == 0 ? files.latest() : files.latest(types), WORKER);
    }

    /**
     * Findings for a log that is already on the site, in the given language.
     *
     * <p>Delegates to the API rather than replaying anything stored locally —
     * see {@link AlaLoggerApi#insights}.
     */
    public CompletableFuture<java.util.List<day.alacraft.alalogger.api.Insight>> insights(
            String id, String language) {
        return api.insights(id, Messages.siteLocale(language));
    }

    /**
     * The host uploads go to, for error messages that name it.
     *
     * <p>Delegated rather than exposing the client itself: naming the host is
     * the only thing the command layer needs it for.
     */
    public String host() {
        return api.host();
    }

    /** Findings for an earlier upload, by id or {@code last}. */
    public Optional<UploadRecord> recall(String id) {
        return "last".equalsIgnoreCase(id) ? history.last() : history.findById(id);
    }

    /**
     * Read limits, preferring what the server publishes over what we assume.
     *
     * <p>Never blocks: this is called from a command handler that may be on the
     * server thread, so a cold cache uses the built-in defaults and the fetch
     * that warms it runs in the background. Being one upload late with an
     * updated limit is harmless; a network round trip inside a tick is not.
     */
    private ReadLimits limits() {
        return api.cachedLimits()
                .map(l -> new ReadLimits(l.maxLength(), l.maxLines()))
                .orElseGet(() -> {
                    api.limits();
                    return ReadLimits.DEFAULT;
                });
    }

    private void remember(UploadResult result, LogFile file) {
        if (!config.persistHistory || !result.isDeletable()) {
            return;
        }

        history.add(result.id(), result.url(), result.token(), file.name());
    }

    /** Warm the limits cache, so the first upload of a session is not the one that discovers them. */
    public void warmUp() {
        api.limits().exceptionally(error -> {
            AlaLogger.LOGGER.debug("Could not fetch upload limits yet ({}).", ApiException.of(error).getMessage());
            return Limits.DEFAULTS;
        });
    }

    /**
     * A finished upload, with everything the presentation layer needs to talk
     * about it — and nothing about how to say it.
     */
    public record Upload(
            UploadResult result,
            RedactionResult redaction,
            boolean truncated,
            ReadMode mode,
            LogFile file) {
    }

    /** The file exists but has nothing in it — worth its own message. */
    public static final class EmptyLogException extends RuntimeException {
        public EmptyLogException(String fileName) {
            super(fileName);
        }
    }

    /** No record of that upload on this server, so no token to delete it with. */
    public static final class UnknownUploadException extends RuntimeException {
        public UnknownUploadException(String id) {
            super(id);
        }
    }
}
