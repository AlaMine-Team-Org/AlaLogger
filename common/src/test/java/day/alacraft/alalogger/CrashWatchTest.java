package day.alacraft.alalogger;

import day.alacraft.alalogger.logs.LogFile;
import day.alacraft.alalogger.logs.LogFiles;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The behaviour that matters here is restraint: a crash watcher that repeats
 * itself, or that greets a fresh install with a year of history, gets muted by
 * its owner and then never helps again.
 */
class CrashWatchTest {

    private CrashWatch watch(Path game, Path marker) {
        return new CrashWatch(marker, LogFiles.forGameDirectory(game));
    }

    /**
     * A watcher whose only JVM crash location is {@code jvmCrashDirectory},
     * given explicitly rather than discovered — the discovered set includes
     * {@code java.io.tmpdir}, which on a developer's machine is tens of
     * thousands of files per listing.
     */
    private CrashWatch watch(Path marker, Path game, Path jvmCrashDirectory) {
        return new CrashWatch(marker, new LogFiles(game, List.of(jvmCrashDirectory), null));
    }

    /** A JVM fatal error log — the file type whose name can collide across directories. */
    private Path jvmCrash(Path directory, Instant modified) throws IOException {
        Files.createDirectories(directory);
        Path file = directory.resolve("hs_err_pid1234.log");
        Files.writeString(file, "# A fatal error has been detected by the Java Runtime Environment\n",
                StandardCharsets.UTF_8);
        Files.setLastModifiedTime(file, FileTime.from(modified));
        return file.toRealPath();
    }

    private Path crashReport(Path game, String name, Instant modified) throws IOException {
        Path dir = Files.createDirectories(game.resolve("crash-reports"));
        Path file = dir.resolve(name);
        Files.writeString(file, "---- Minecraft Crash Report ----\nDescription: Ticking entity\n",
                StandardCharsets.UTF_8);
        Files.setLastModifiedTime(file, FileTime.from(modified));
        return file;
    }

    @Test
    void a_fresh_crash_is_reported_once_and_never_again(@TempDir Path game) throws IOException {
        crashReport(game, "crash-2026-08-29_01.00.00-server.txt", Instant.now());
        Path marker = game.resolve("marker.json");

        List<LogFile> first = watch(game, marker).unreported();
        assertEquals(1, first.size(), "the crash should be offered on the first start after it");

        List<LogFile> second = watch(game, marker).unreported();
        assertTrue(second.isEmpty(), "and never again — otherwise every restart repeats it");
    }

    /**
     * Installing the mod on a server that has been crashing for a year must not
     * produce a year of history.
     */
    @Test
    void old_crashes_are_not_dug_up_on_a_first_run(@TempDir Path game) throws IOException {
        crashReport(game, "crash-2025-01-01_00.00.00-server.txt", Instant.now().minus(Duration.ofDays(300)));
        crashReport(game, "crash-2026-08-29_01.00.00-server.txt", Instant.now());

        List<LogFile> found = watch(game, game.resolve("marker.json")).unreported();

        assertEquals(1, found.size());
        assertTrue(found.get(0).name().contains("2026-08-29"));
    }

    /**
     * Once the mod has run, the time window no longer applies — a crash from an
     * hour ago is new even if the server was down for a week before it.
     */
    @Test
    void after_the_first_run_age_no_longer_matters(@TempDir Path game) throws IOException {
        Path marker = game.resolve("marker.json");
        crashReport(game, "crash-first.txt", Instant.now());
        watch(game, marker).unreported();

        crashReport(game, "crash-older.txt", Instant.now().minus(Duration.ofDays(30)));

        List<LogFile> found = watch(game, marker).unreported();

        assertEquals(1, found.size());
        assertEquals("crash-older.txt", found.get(0).name());
    }

    @Test
    void ordinary_logs_are_not_crashes(@TempDir Path game) throws IOException {
        Path logs = Files.createDirectories(game.resolve("logs"));
        Files.writeString(logs.resolve("latest.log"), "[00:00:01] [Server thread/INFO]: hello\n");

        assertTrue(watch(game, game.resolve("marker.json")).unreported().isEmpty());
    }

    /**
     * A damaged marker must not turn into a wall of old crashes — it falls back
     * to first-run behaviour, which re-applies the time window.
     */
    @Test
    void a_corrupt_marker_does_not_replay_history(@TempDir Path game) throws IOException {
        Path marker = game.resolve("marker.json");
        Files.writeString(marker, "{ this is not json", StandardCharsets.UTF_8);

        crashReport(game, "crash-ancient.txt", Instant.now().minus(Duration.ofDays(200)));
        crashReport(game, "crash-recent.txt", Instant.now());

        List<LogFile> found = watch(game, marker).unreported();

        assertEquals(1, found.size());
        assertEquals("crash-recent.txt", found.get(0).name());
    }

    @Test
    void nothing_to_report_on_an_empty_server(@TempDir Path game) {
        assertTrue(watch(game, game.resolve("marker.json")).unreported().isEmpty());
    }

    /**
     * Two crash files can carry the same name at once: HotSpot writes
     * {@code hs_err_pid<PID>.log} beside the game or into {@code java.io.tmpdir},
     * and pids are reused. Remembering the name rather than the path meant the
     * second one was never offered at all.
     */
    @Test
    void a_second_crash_with_the_same_name_elsewhere_is_still_offered(
            @TempDir Path game, @TempDir Path elsewhere) throws IOException {
        Path marker = game.resolve("marker.json");

        Path first = jvmCrash(game, Instant.now());
        List<LogFile> firstRun = watch(marker, game, elsewhere).unreported();
        assertEquals(List.of(first), firstRun.stream().map(LogFile::path).toList());

        Path second = jvmCrash(elsewhere, Instant.now());
        List<LogFile> secondRun = watch(marker, game, elsewhere).unreported();

        assertEquals(1, secondRun.size(), "the same name in another directory is a different crash");
        assertEquals(second, secondRun.get(0).path());

        assertTrue(watch(marker, game, elsewhere).unreported().isEmpty(), "and neither is offered twice");
    }

    /**
     * Markers from before the path change hold bare file names, which would
     * match nothing — with the window already closed that would replay every
     * crash file on the disk, so such a marker falls back to first-run
     * behaviour instead.
     */
    @Test
    void a_marker_from_an_older_schema_does_not_replay_history(@TempDir Path game) throws IOException {
        Path marker = game.resolve("marker.json");
        Files.writeString(marker, "{\"schemaVersion\":1,\"seen\":[\"crash-ancient.txt\"]}",
                StandardCharsets.UTF_8);

        crashReport(game, "crash-ancient.txt", Instant.now().minus(Duration.ofDays(200)));
        crashReport(game, "crash-recent.txt", Instant.now());

        List<LogFile> found = watch(game, marker).unreported();

        assertEquals(1, found.size());
        assertEquals("crash-recent.txt", found.get(0).name());
    }

    /**
     * The marker is composed beside itself and moved into place, so a process
     * killed mid-write cannot leave a half-written file — which would read as a
     * first run and offer a week of crashes again.
     */
    @Test
    void the_marker_is_written_atomically(@TempDir Path game) throws IOException {
        Path marker = game.resolve("marker.json");
        Path temporary = game.resolve("marker.json.tmp");

        // A leftover from a run that was killed before the move.
        Files.writeString(temporary, "{ half written", StandardCharsets.UTF_8);

        crashReport(game, "crash-fresh.txt", Instant.now());
        assertEquals(1, watch(game, marker).unreported().size());

        assertFalse(Files.exists(temporary), "the temporary file must not survive the write");
        assertTrue(watch(game, marker).unreported().isEmpty(),
                "and what landed must be a complete, readable marker");
    }

    /** The marker is written even when the first run found nothing, so the window closes. */
    @Test
    void the_window_closes_after_the_first_run(@TempDir Path game) throws IOException {
        Path marker = game.resolve("marker.json");
        watch(game, marker).unreported();

        assertTrue(Files.exists(marker), "a first run with no crashes still records that it happened");

        crashReport(game, "crash-old-but-new-to-us.txt", Instant.now().minus(Duration.ofDays(60)));

        assertFalse(watch(game, marker).unreported().isEmpty(),
                "after the first run, an old file we have not seen is still news");
    }
}
