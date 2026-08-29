package day.alacraft.alalogger.logs;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LogReaderTest {

    /** U+FFFD, what a decoder substitutes for a byte it cannot make sense of. */
    private static final char REPLACEMENT_CHARACTER = 0xFFFD;

    /** The three bytes of a UTF-8 byte-order mark, spelled out so they are visible. */
    private static final byte[] BYTE_ORDER_MARK = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    @TempDir
    Path directory;

    /** Fixed-width lines "line 0001" .. "line NNNN": exactly ten bytes each, newline included. */
    private static String numbered(int count) {
        StringBuilder text = new StringBuilder();
        for (int i = 1; i <= count; i++) {
            text.append("line %04d\n".formatted(i));
        }
        return text.toString();
    }

    private Path write(String name, String content) throws IOException {
        Path file = directory.resolve(name);
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    private Path writeGzipped(String name, String content) throws IOException {
        Path file = directory.resolve(name);
        try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(file))) {
            out.write(content.getBytes(StandardCharsets.UTF_8));
        }
        return file;
    }

    private static LogFile asFile(Path path, LogFileType type) throws IOException {
        return new LogFile(path, path.getFileName().toString(), type, Files.size(path), Instant.now());
    }

    @Test
    @DisplayName("a file inside the limits comes back whole and unflagged")
    void readsSmallFilesWhole() throws IOException {
        Path file = write("latest.log", "first\nsecond\nthird\n");

        LogContent content = LogReader.read(file, ReadMode.TAIL, ReadLimits.DEFAULT);

        assertEquals("first\nsecond\nthird", content.text());
        assertEquals(3, content.lines());
        assertFalse(content.truncated());
        assertEquals(ReadMode.TAIL, content.mode());
    }

    @Test
    @DisplayName("the tail is what a live log needs - the exception is at the bottom")
    void tailKeepsTheEnd() throws IOException {
        Path file = write("latest.log", numbered(100));

        LogContent content = LogReader.read(file, ReadMode.TAIL, new ReadLimits(ReadLimits.DEFAULT_MAX_BYTES, 10));

        assertEquals(10, content.lines());
        assertTrue(content.truncated());
        assertTrue(content.text().startsWith("line 0091"));
        assertTrue(content.text().endsWith("line 0100"));
    }

    @Test
    @DisplayName("the head is what a crash report needs - the cause is at the top")
    void headKeepsTheBeginning() throws IOException {
        Path file = write("crash.txt", numbered(100));

        LogContent content = LogReader.read(file, ReadMode.HEAD, new ReadLimits(ReadLimits.DEFAULT_MAX_BYTES, 10));

        assertEquals(10, content.lines());
        assertTrue(content.truncated());
        assertTrue(content.text().startsWith("line 0001"));
        assertTrue(content.text().endsWith("line 0010"));
    }

    @Test
    @DisplayName("the file type picks the end, so a crash report is never read from the tail")
    void theTypeChoosesTheMode() throws IOException {
        Path log = write("latest.log", numbered(100));
        Path crash = write("crash.txt", numbered(100));
        Path jvm = write("hs_err_pid1.log", numbered(100));
        Path network = write("disconnect.txt", numbered(100));

        ReadLimits limits = new ReadLimits(ReadLimits.DEFAULT_MAX_BYTES, 5);

        assertTrue(LogReader.read(asFile(log, LogFileType.LOG), limits).text().startsWith("line 0096"));
        assertTrue(LogReader.read(asFile(crash, LogFileType.CRASH_REPORT), limits).text().startsWith("line 0001"));
        assertTrue(LogReader.read(asFile(jvm, LogFileType.JVM_CRASH), limits).text().startsWith("line 0001"));
        assertTrue(LogReader.read(asFile(network, LogFileType.NETWORK_REPORT), limits).text().startsWith("line 0001"));
    }

    @Test
    @DisplayName("the byte budget is respected and the seek path keeps whole lines")
    void tailRespectsTheByteLimit() throws IOException {
        // 100 lines x 10 bytes = 1000 bytes. A 205 byte budget makes the reader
        // seek to offset 795, which lands in the middle of line 0080 - the
        // partial line has to be dropped rather than uploaded as a fragment.
        Path file = write("latest.log", numbered(100));

        LogContent content = LogReader.read(file, ReadMode.TAIL, new ReadLimits(205, ReadLimits.DEFAULT_MAX_LINES));

        assertEquals(20, content.lines());
        assertEquals("line 0081", content.text().lines().findFirst().orElseThrow());
        assertTrue(content.text().endsWith("line 0100"));
        assertTrue(content.truncated());
        assertTrue(content.text().getBytes(StandardCharsets.UTF_8).length <= 205);
    }

    @Test
    void headRespectsTheByteLimit() throws IOException {
        Path file = write("crash.txt", numbered(100));

        LogContent content = LogReader.read(file, ReadMode.HEAD, new ReadLimits(100, ReadLimits.DEFAULT_MAX_LINES));

        assertEquals(10, content.lines());
        assertTrue(content.truncated());
        assertTrue(content.text().getBytes(StandardCharsets.UTF_8).length <= 100);
        assertTrue(content.text().startsWith("line 0001"));
    }

    @Test
    @DisplayName("a rotated .gz is decompressed on the fly, from either end")
    void readsGzip() throws IOException {
        Path file = writeGzipped("2026-08-28-1.log.gz", numbered(100));

        LogContent tail = LogReader.read(file, ReadMode.TAIL, new ReadLimits(ReadLimits.DEFAULT_MAX_BYTES, 3));
        assertEquals(3, tail.lines());
        assertEquals("line 0098\nline 0099\nline 0100", tail.text());
        assertTrue(tail.truncated());

        LogContent head = LogReader.read(file, ReadMode.HEAD, new ReadLimits(ReadLimits.DEFAULT_MAX_BYTES, 3));
        assertEquals("line 0001\nline 0002\nline 0003", head.text());
    }

    @Test
    @DisplayName("gzip is detected by its bytes, so a mis-named rotated log still reads")
    void detectsGzipByContentNotByName() throws IOException {
        Path file = writeGzipped("latest.log", "compressed\n");

        assertEquals("compressed", LogReader.read(file, ReadMode.TAIL, ReadLimits.DEFAULT).text());
    }

    @Test
    @DisplayName("an undecodable byte becomes a replacement character instead of an exception")
    void survivesInvalidUtf8() throws IOException {
        Path file = directory.resolve("latest.log");
        // 0xC3 starts a two-byte sequence that 0x28 does not finish - the classic
        // way a Windows-1251 file name ends up inside a UTF-8 log.
        Files.write(file, new byte[]{'A', (byte) 0xC3, 0x28, 'B', '\n', 'C', '\n'});

        LogContent content = LogReader.read(file, ReadMode.TAIL, ReadLimits.DEFAULT);

        assertEquals(2, content.lines());
        assertTrue(content.text().contains("A"));
        assertTrue(content.text().contains("B"));
        assertTrue(content.text().endsWith("C"));
        assertTrue(content.text().indexOf(REPLACEMENT_CHARACTER) >= 0);
    }

    @Test
    void readsAnEmptyFile() throws IOException {
        Path file = write("latest.log", "");

        LogContent content = LogReader.read(file, ReadMode.TAIL, ReadLimits.DEFAULT);

        assertTrue(content.isEmpty());
        assertEquals(0, content.lines());
        assertFalse(content.truncated());
    }

    @Test
    @DisplayName("line endings are normalised, because the byte budget is counted on the output")
    void normalisesLineEndings() throws IOException {
        Path file = write("latest.log", "first\r\nsecond\r\nthird");

        LogContent content = LogReader.read(file, ReadMode.HEAD, ReadLimits.DEFAULT);

        assertEquals("first\nsecond\nthird", content.text());
        assertEquals(3, content.lines());
    }

    @Test
    @DisplayName("a byte-order mark is not left in front of the first word")
    void stripsAByteOrderMark() throws IOException {
        Path file = directory.resolve("crash.txt");
        byte[] body = "---- Minecraft Crash Report ----\n".getBytes(StandardCharsets.UTF_8);
        byte[] withMark = new byte[BYTE_ORDER_MARK.length + body.length];
        System.arraycopy(BYTE_ORDER_MARK, 0, withMark, 0, BYTE_ORDER_MARK.length);
        System.arraycopy(body, 0, withMark, BYTE_ORDER_MARK.length, body.length);
        Files.write(file, withMark);

        LogContent content = LogReader.read(file, ReadMode.HEAD, ReadLimits.DEFAULT);

        assertEquals("---- Minecraft Crash Report ----", content.text());
    }

    @Test
    @DisplayName("nonsense limits fall back to the defaults rather than uploading nothing")
    void repairsBrokenLimits() {
        assertEquals(ReadLimits.DEFAULT_MAX_BYTES, new ReadLimits(0, 10).maxBytes());
        assertEquals(ReadLimits.DEFAULT_MAX_LINES, new ReadLimits(10, -1).maxLines());
    }

    @Test
    @DisplayName("a single line larger than the whole budget truncates instead of overshooting it")
    void refusesToExceedTheBudget() throws IOException {
        Path file = write("latest.log", "x".repeat(500) + "\n");

        LogContent tail = LogReader.read(file, ReadMode.TAIL, new ReadLimits(50, ReadLimits.DEFAULT_MAX_LINES));
        assertTrue(tail.truncated());
        assertTrue(tail.text().getBytes(StandardCharsets.UTF_8).length <= 50);

        LogContent head = LogReader.read(file, ReadMode.HEAD, new ReadLimits(50, ReadLimits.DEFAULT_MAX_LINES));
        assertTrue(head.truncated());
        assertTrue(head.text().getBytes(StandardCharsets.UTF_8).length <= 50);
    }

    @Test
    @DisplayName("multi-byte text is measured in bytes, not characters")
    void countsUtf8Bytes() throws IOException {
        // Five two-byte Cyrillic letters plus a newline is eleven bytes per line,
        // so a 33 byte budget holds three lines - not the thirty-three a reader
        // counting characters would have taken.
        Path file = write("latest.log", "абвгд\n".repeat(10));

        LogContent content = LogReader.read(file, ReadMode.HEAD, new ReadLimits(33, ReadLimits.DEFAULT_MAX_LINES));

        assertEquals(3, content.lines());
        assertTrue(content.truncated());
        assertTrue(content.text().getBytes(StandardCharsets.UTF_8).length <= 33);
    }
}
