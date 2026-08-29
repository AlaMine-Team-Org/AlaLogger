package day.alacraft.alalogger.logs;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.zip.GZIPInputStream;

/**
 * Reads as much of a file as may be uploaded, from the end that matters.
 *
 * <p>Three decisions are worth the reader's time.
 *
 * <p><b>Tail by default.</b> See {@link ReadMode}. A live log appends its
 * failure at the bottom, so keeping the first 10 MB of a 40 MB
 * {@code latest.log} uploads the startup banner and discards the exception. Crash reports are the reverse and
 * are read from the head.
 *
 * <p><b>Broken bytes never stop a read.</b> Logs are written by whatever wrote
 * them: log4j in UTF-8, a mod printing a Windows-1251 file name, a JVM dumping
 * raw stack memory into an {@code hs_err} file. Decoding is UTF-8 with
 * replacement, so an undecodable byte becomes U+FFFD and the read continues.
 * Refusing to read a crash log because byte 4 000 000 is malformed would fail
 * exactly the person who most needs it to work.
 *
 * <p><b>The byte budget is counted on the output, not the input.</b> Line
 * endings are normalised to {@code \n} on the way out, so counting what we
 * produce is the only count that predicts the size of the upload.
 */
public final class LogReader {

    private static final int BUFFER_SIZE = 64 * 1024;

    /** The two bytes every gzip stream starts with. */
    private static final int GZIP_MAGIC_FIRST = 0x1f;
    private static final int GZIP_MAGIC_SECOND = 0x8b;

    /** U+FEFF, which the UTF-8 decoder hands through as an ordinary character. */
    private static final char BYTE_ORDER_MARK = 0xFEFF;

    private LogReader() {
    }

    /** Reads a discovered file, from the end its type calls for. */
    public static LogContent read(LogFile file, ReadLimits limits) throws IOException {
        return read(file.path(), file.readMode(), limits);
    }

    /** Reads any path in an explicit mode. */
    public static LogContent read(Path path, ReadMode mode, ReadLimits limits) throws IOException {
        boolean compressed = isGzip(path);
        return mode == ReadMode.HEAD
                ? readHead(path, compressed, limits)
                : readTail(path, compressed, limits);
    }

    /**
     * Keeps the beginning: everything a crash report needs is in its first
     * screenful, and reading further would only push the cause out of the
     * budget behind a thousand lines of mod list.
     */
    private static LogContent readHead(Path path, boolean compressed, ReadLimits limits) throws IOException {
        List<String> lines = new ArrayList<>();
        long bytes = 0;
        boolean truncated = false;

        try (BufferedReader reader = open(path, compressed, 0)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (lines.size() >= limits.maxLines()) {
                    truncated = true;
                    break;
                }

                long cost = utf8Length(line) + 1;
                if (bytes + cost > limits.maxBytes()) {
                    // Stop rather than overshoot, even if that means keeping
                    // nothing at all. A single line longer than the whole budget
                    // is pathological, and an upload the server rejects for size
                    // is worse than an honest "truncated, nothing fit".
                    truncated = true;
                    break;
                }

                lines.add(line);
                bytes += cost;
            }
        }

        return new LogContent(stripBom(String.join("\n", lines)), lines.size(), truncated, ReadMode.HEAD);
    }

    /**
     * Keeps the end.
     *
     * <p>An uncompressed file is seeked to {@code size - maxBytes} first, so a
     * 400 MB {@code latest.log} costs one read of the last 10 MB instead of a
     * full decode of the whole thing. A gzip stream cannot be seeked, so it is
     * streamed through a window that holds only the lines still in budget —
     * memory stays bounded by the limits either way.
     */
    private static LogContent readTail(Path path, boolean compressed, ReadLimits limits) throws IOException {
        long skip = 0;
        if (!compressed) {
            long size = Files.size(path);
            if (size > limits.maxBytes()) {
                skip = size - limits.maxBytes();
            }
        }

        Deque<String> lines = new ArrayDeque<>();
        long bytes = 0;
        boolean truncated = skip > 0;

        try (BufferedReader reader = open(path, compressed, skip)) {
            String line;
            while ((line = reader.readLine()) != null) {
                lines.addLast(line);
                bytes += utf8Length(line) + 1;

                while (lines.size() > limits.maxLines() || bytes > limits.maxBytes()) {
                    bytes -= utf8Length(lines.removeFirst()) + 1;
                    truncated = true;
                }
            }
        }

        // Seeking to a byte offset almost always lands mid-line, and half a
        // stack frame at the top of an upload reads like corruption. Dropping it
        // can cost one whole line when the offset happened to fall on a newline;
        // that is invisible at a 10 MB boundary, a mangled first line is not.
        if (skip > 0 && lines.size() > 1) {
            lines.removeFirst();
        }

        return new LogContent(stripBom(String.join("\n", lines)), lines.size(), truncated, ReadMode.TAIL);
    }

    /**
     * Opens the file decoded, optionally decompressed, optionally seeked.
     *
     * <p>Decoding uses replacement rather than the default report-and-throw:
     * see the class comment. A malformed byte in the middle of a crash log must
     * not turn into an exception in the middle of an upload.
     */
    private static BufferedReader open(Path path, boolean compressed, long skip) throws IOException {
        InputStream raw = Files.newInputStream(path);
        try {
            if (skip > 0) {
                // Seekable for a real file: this positions the channel rather
                // than reading and discarding.
                raw.skipNBytes(skip);
            }

            InputStream stream = compressed ? new GZIPInputStream(raw, BUFFER_SIZE) : raw;

            return new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPLACE)
                    .onUnmappableCharacter(CodingErrorAction.REPLACE)), BUFFER_SIZE);
        } catch (IOException | RuntimeException e) {
            raw.close();
            throw e;
        }
    }

    /**
     * Whether the file is gzip, decided by its first two bytes.
     *
     * <p>By content and not by name: log rotation configurations do get changed,
     * and a {@code .gz} that is not gzip — or gzip that is not named {@code .gz}
     * — should still be readable rather than produce a stream of mojibake or an
     * exception.
     */
    private static boolean isGzip(Path path) throws IOException {
        try (InputStream in = Files.newInputStream(path)) {
            return in.read() == GZIP_MAGIC_FIRST && in.read() == GZIP_MAGIC_SECOND;
        }
    }

    /**
     * Removes a byte-order mark.
     *
     * <p>Java's UTF-8 decoder passes U+FEFF through as an ordinary character, and
     * it then shows up as a stray glyph before the first word of the log.
     */
    private static String stripBom(String text) {
        // Compared as a code point rather than written as a literal: a bare BOM in
        // source is invisible in every editor, and the one place it must not go
        // unnoticed is the code that removes it.
        return !text.isEmpty() && text.charAt(0) == BYTE_ORDER_MARK ? text.substring(1) : text;
    }

    /**
     * UTF-8 length of a string without encoding it.
     *
     * <p>Called once per line over a file that can be hundreds of megabytes, so
     * the obvious {@code getBytes(UTF_8).length} would allocate and discard the
     * whole file a line at a time.
     */
    private static long utf8Length(String text) {
        long length = 0;

        for (int i = 0; i < text.length(); i++) {
            char current = text.charAt(i);

            if (current < 0x80) {
                length += 1;
            } else if (current < 0x800) {
                length += 2;
            } else if (Character.isHighSurrogate(current)
                    && i + 1 < text.length()
                    && Character.isLowSurrogate(text.charAt(i + 1))) {
                // One code point spread over two chars: four bytes, not two threes.
                length += 4;
                i++;
            } else {
                length += 3;
            }
        }

        return length;
    }
}
