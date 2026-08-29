package day.alacraft.alalogger.logs;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LogFilesTest {

    @TempDir
    Path root;

    private Path game;
    private Path logs;
    private Path crashReports;
    private Path debug;
    private Path workingDirectory;
    private Path temporaryDirectory;

    @BeforeEach
    void layOutADisk() throws IOException {
        game = Files.createDirectories(root.resolve("game"));
        logs = Files.createDirectories(game.resolve("logs"));
        crashReports = Files.createDirectories(game.resolve("crash-reports"));
        debug = Files.createDirectories(game.resolve("debug"));
        workingDirectory = Files.createDirectories(root.resolve("work"));
        temporaryDirectory = Files.createDirectories(root.resolve("tmp"));
    }

    private LogFiles files() {
        return new LogFiles(game, List.of(workingDirectory, temporaryDirectory), null);
    }

    private Path write(Path directory, String name, Instant modified) throws IOException {
        Path file = directory.resolve(name);
        Files.writeString(file, name + " content\n", StandardCharsets.UTF_8);
        Files.setLastModifiedTime(file, FileTime.from(modified));
        return file;
    }

    @Test
    @DisplayName("when Minecraft writes latest.log and debug.log in the same instant, latest.log is the one meant")
    void prefersTheCurrentLogOverDebugOnAnExactTie() throws IOException {
        // Not a contrived tie: the game writes both files in one operation, so
        // this is what every real logs/ directory looks like. Ordering them by
        // name instead sent debug.log - twenty times larger, and the first to be
        // truncated against the size limit - every single time.
        Instant sameInstant = Instant.parse("2026-08-29T10:15:59Z");
        write(logs, "debug.log", sameInstant);
        write(logs, "latest.log", sameInstant);

        assertEquals("latest.log", files().latest(LogFileType.LOG).orElseThrow().name());
        assertEquals("latest.log", files().list().getFirst().name());
    }

    private static Optional<LogFile> named(List<LogFile> files, String name) {
        return files.stream().filter(file -> file.name().equals(name)).findFirst();
    }

    @Test
    @DisplayName("finds all four kinds, including the hs_err no competitor looks for")
    void findsEveryKind() throws IOException {
        Instant now = Instant.now();
        write(logs, "latest.log", now);
        write(crashReports, "crash-2026-08-28_15.10.58-server.txt", now.minus(1, ChronoUnit.MINUTES));
        write(debug, "disconnect-2026-08-28-20-39-43-client.txt", now.minus(2, ChronoUnit.MINUTES));
        write(workingDirectory, "hs_err_pid10280.log", now.minus(3, ChronoUnit.MINUTES));
        write(temporaryDirectory, "hs_err_pid20512.log", now.minus(4, ChronoUnit.MINUTES));

        List<LogFile> found = files().list();

        assertEquals(5, found.size(), found.toString());
        assertEquals(LogFileType.LOG, named(found, "latest.log").orElseThrow().type());
        assertEquals(LogFileType.CRASH_REPORT,
                named(found, "crash-2026-08-28_15.10.58-server.txt").orElseThrow().type());
        assertEquals(LogFileType.NETWORK_REPORT,
                named(found, "disconnect-2026-08-28-20-39-43-client.txt").orElseThrow().type());
        assertEquals(LogFileType.JVM_CRASH, named(found, "hs_err_pid10280.log").orElseThrow().type());
        assertEquals(LogFileType.JVM_CRASH, named(found, "hs_err_pid20512.log").orElseThrow().type());
    }

    @Test
    @DisplayName("an hs_err in the game root is found too - that is where a server's JVM drops it")
    void findsJvmCrashInTheGameRoot() throws IOException {
        write(game, "hs_err_pid777.log", Instant.now());

        assertEquals(LogFileType.JVM_CRASH, named(files().list(), "hs_err_pid777.log").orElseThrow().type());
    }

    @Test
    @DisplayName("accepts rotated and gzipped names, refuses everything that is not a log")
    void appliesTheNameWhitelist() throws IOException {
        Instant now = Instant.now();
        write(logs, "latest.log", now);
        write(logs, "2026-08-28-1.log.gz", now);
        write(logs, "debug.log.1", now);
        write(logs, "notes.txt", now);
        write(logs, "options.json", now);
        write(logs, "world.zip", now);
        write(logs, "latest.log.bak", now);

        List<String> names = files().list().stream().map(LogFile::name).sorted().toList();

        assertEquals(List.of("2026-08-28-1.log.gz", "debug.log.1", "latest.log", "notes.txt"), names);
    }

    @Test
    @DisplayName("newest first, because the file somebody wants is the last one written")
    void sortsByModificationTime() throws IOException {
        Instant now = Instant.now();
        write(logs, "oldest.log", now.minus(3, ChronoUnit.HOURS));
        write(logs, "newest.log", now);
        write(crashReports, "crash-middle.txt", now.minus(1, ChronoUnit.HOURS));

        assertEquals(List.of("newest.log", "crash-middle.txt", "oldest.log"),
                files().list().stream().map(LogFile::name).toList());
    }

    @Test
    @DisplayName("the list filter is a case-insensitive substring")
    void filtersBySubstring() throws IOException {
        Instant now = Instant.now();
        write(logs, "2026-08-28-1.log", now);
        write(logs, "2026-08-27-1.log", now);
        write(crashReports, "crash-2026-08-28_15.10.58-server.txt", now);

        assertEquals(2, files().list("08-28").size());
        assertEquals(1, files().list("CRASH").size());
        assertEquals(0, files().list("nothing").size());
        assertEquals(3, files().list("  ").size());
        assertEquals(3, files().list(null).size());
    }

    @Test
    @DisplayName("size and age come back with the listing, so /alog list needs no second look at the disk")
    void carriesMetadata() throws IOException {
        Instant written = Instant.now().minus(5, ChronoUnit.MINUTES);
        Path file = logs.resolve("latest.log");
        Files.writeString(file, "0123456789", StandardCharsets.UTF_8);
        Files.setLastModifiedTime(file, FileTime.from(written));

        LogFile found = files().list().get(0);

        assertEquals(10, found.size());
        assertEquals(written.truncatedTo(ChronoUnit.SECONDS),
                found.modified().truncatedTo(ChronoUnit.SECONDS));
        assertTrue(found.age(Instant.now()).toMinutes() >= 4);
    }

    @Test
    @DisplayName("a future timestamp does not produce a negative age")
    void clampsFutureAges() throws IOException {
        write(logs, "latest.log", Instant.now().plus(1, ChronoUnit.HOURS));

        assertFalse(files().list().get(0).age(Instant.now()).isNegative());
    }

    @Test
    void findsAFileByName() throws IOException {
        write(logs, "latest.log", Instant.now());

        LogFile found = files().find("latest.log").orElseThrow();

        assertEquals("latest.log", found.name());
        assertEquals(LogFileType.LOG, found.type());
    }

    @Test
    @DisplayName("a traversing argument is refused - the name comes from a game command")
    void refusesPathTraversal() throws IOException {
        // Outside the game directory entirely, which is where a hostile argument
        // is aiming: a config file, an .env, a key.
        Files.writeString(root.resolve("secret.log"), "not yours\n", StandardCharsets.UTF_8);

        LogFiles files = files();

        assertTrue(files.find("../../secret.log").isEmpty());
        assertTrue(files.find("..\\..\\secret.log").isEmpty());
        assertTrue(files.find("../secret.log").isEmpty());
        // An absolute path is not a way round it either.
        assertTrue(files.find(root.resolve("secret.log").toString()).isEmpty());
    }

    @Test
    @DisplayName("a file one level down is refused - only the directories themselves are allowed")
    void refusesSubdirectories() throws IOException {
        Path nested = Files.createDirectories(logs.resolve("archive"));
        write(nested, "old.log", Instant.now());

        LogFiles files = files();

        assertTrue(files.find("archive/old.log").isEmpty());
        assertTrue(files.list().isEmpty());
    }

    @Test
    @DisplayName("a symlink planted in logs/ is refused - the target is resolved, not just the parent")
    void refusesEscapingSymlinks() throws IOException {
        Path secret = root.resolve("secret.log");
        Files.writeString(secret, "not yours\n", StandardCharsets.UTF_8);

        Path link = logs.resolve("innocent.log");
        try {
            Files.createSymbolicLink(link, secret);
        } catch (IOException | UnsupportedOperationException e) {
            Assumptions.abort("This machine cannot create symlinks: " + e);
        }

        LogFiles files = files();

        assertTrue(files.find("innocent.log").isEmpty());
        assertTrue(files.list().isEmpty());
    }

    @Test
    @DisplayName("a symlink inside the allowed directory is fine - that is not an escape")
    void allowsSymlinksThatStayInside() throws IOException {
        write(logs, "2026-08-28-1.log", Instant.now());

        Path link = logs.resolve("latest.log");
        try {
            Files.createSymbolicLink(link, logs.resolve("2026-08-28-1.log"));
        } catch (IOException | UnsupportedOperationException e) {
            Assumptions.abort("This machine cannot create symlinks: " + e);
        }

        assertTrue(files().find("latest.log").isPresent());
        // The link and its target are one file, listed once.
        assertEquals(1, files().list().size());
    }

    @Test
    void refusesNamesThatAreNotLogs() throws IOException {
        write(logs, "options.json", Instant.now());

        assertTrue(files().find("options.json").isEmpty());
        assertTrue(files().find("missing.log").isEmpty());
        assertTrue(files().find("").isEmpty());
        assertTrue(files().find(null).isEmpty());
    }

    @Test
    @DisplayName("a directory named like a log is not a file")
    void refusesDirectories() throws IOException {
        Files.createDirectories(logs.resolve("archived.log"));

        assertTrue(files().find("archived.log").isEmpty());
        assertTrue(files().list().isEmpty());
    }

    @Test
    @DisplayName("latest(type) is what lets /alog crash work without knowing a file name")
    void findsTheNewestOfAType() throws IOException {
        Instant now = Instant.now();
        write(logs, "latest.log", now);
        write(crashReports, "crash-old.txt", now.minus(2, ChronoUnit.HOURS));
        write(crashReports, "crash-new.txt", now.minus(1, ChronoUnit.HOURS));
        write(workingDirectory, "hs_err_pid1.log", now.minus(3, ChronoUnit.HOURS));

        LogFiles files = files();

        assertEquals("latest.log", files.latest().orElseThrow().name());
        assertEquals("crash-new.txt",
                files.latest(LogFileType.CRASH_REPORT).orElseThrow().name());
        assertEquals("crash-new.txt",
                files.latest(LogFileType.CRASH_REPORT, LogFileType.JVM_CRASH).orElseThrow().name());
        assertTrue(files.latest(LogFileType.NETWORK_REPORT).isEmpty());
    }

    @Test
    @DisplayName("a working directory that is the game directory is scanned once, not twice")
    void deduplicatesOverlappingDirectories() throws IOException {
        write(game, "hs_err_pid42.log", Instant.now());

        LogFiles files = new LogFiles(game, List.of(game, game.resolve("logs").resolve("..")), null);

        assertEquals(1, files.list().size());
    }

    @Test
    @DisplayName("missing directories are not an error - a fresh server has no crash-reports/")
    void toleratesMissingDirectories() throws IOException {
        Files.delete(crashReports);
        Files.delete(debug);
        write(logs, "latest.log", Instant.now());

        assertEquals(1, files().list().size());
    }

    @Test
    void readsTheErrorFileFlag() {
        Path work = root.resolve("work");

        assertTrue(LogFiles.errorFileFrom(List.of("-Xmx4G"), work).isEmpty());
        assertTrue(LogFiles.errorFileFrom(List.of(), work).isEmpty());
        assertTrue(LogFiles.errorFileFrom(null, work).isEmpty());

        // Relative templates resolve against the working directory, as HotSpot does.
        assertEquals(work.resolve("hs_err_%p.log"),
                LogFiles.errorFileFrom(List.of("-XX:ErrorFile=hs_err_%p.log"), work).orElseThrow());

        Path absolute = root.resolve("crashes").resolve("jvm_%p.log");
        assertEquals(absolute,
                LogFiles.errorFileFrom(List.of("-Xmx4G", "-XX:ErrorFile=" + absolute), work).orElseThrow());

        // Repeated flags: HotSpot takes the last one, so we do too.
        assertEquals(work.resolve("second.log"), LogFiles.errorFileFrom(
                List.of("-XX:ErrorFile=first.log", "-XX:ErrorFile=second.log"), work).orElseThrow());
    }

    @Test
    @DisplayName("an -XX:ErrorFile with a custom name is found by expanding its template")
    void findsTheConfiguredErrorFile() throws IOException {
        Path crashes = Files.createDirectories(root.resolve("crashes"));
        write(crashes, "jvm_10280.log", Instant.now());
        write(crashes, "unrelated.log", Instant.now());

        LogFiles files = new LogFiles(game, List.of(workingDirectory), crashes.resolve("jvm_%p.log"));
        List<LogFile> found = files.list();

        assertEquals(1, found.size(), found.toString());
        assertEquals("jvm_10280.log", found.get(0).name());
        assertEquals(LogFileType.JVM_CRASH, found.get(0).type());
    }

    @Test
    @DisplayName("an ErrorFile aimed into logs/ is still typed as a JVM crash, so it is read from the head")
    void typesJvmCrashesByNameWhereverTheyLand() throws IOException {
        write(logs, "hs_err_pid99.log", Instant.now());

        LogFile found = files().find("hs_err_pid99.log").orElseThrow();

        assertEquals(LogFileType.JVM_CRASH, found.type());
        assertEquals(ReadMode.HEAD, found.readMode());
    }
}
