package day.alacraft.alalogger.logs;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Listing has to stay cheap, because two callers pay for it on the game thread:
 * tab-completion, which runs while a player is mid-word, and the crash check on
 * startup.
 *
 * <p>This exists because it was NOT cheap. Every candidate went through
 * {@code toRealPath()} — a syscall — before the name filter rejected it, and one
 * of the scanned sources is the system temp directory, which on a real machine
 * holds tens of thousands of files. Measured at ten seconds for a single log.
 * Checking the name first makes the irrelevant files free.
 */
class LogFilesPerformanceTest {

    /** Comfortably under a tick, and two orders of magnitude below what it was. */
    private static final long BUDGET_MS = 1_500;

    private static final int NOISE_FILES = 20_000;

    @Test
    void listing_stays_fast_next_to_a_directory_full_of_unrelated_files(@TempDir Path root) throws IOException {
        Path game = Files.createDirectories(root.resolve("game"));
        Path logs = Files.createDirectories(game.resolve("logs"));
        Files.writeString(logs.resolve("latest.log"), "[00:00:01] [Server thread/INFO]: hi\n");

        // The shape of a real temp directory: a lot of files, none of them ours.
        Path noise = Files.createDirectories(root.resolve("noise"));
        for (int i = 0; i < NOISE_FILES; i++) {
            Files.writeString(noise.resolve("unrelated-" + i + ".tmp"), "x");
        }

        LogFiles files = new LogFiles(game, List.of(noise), null);

        long start = System.nanoTime();
        List<LogFile> found = files.list();
        long millis = (System.nanoTime() - start) / 1_000_000;

        System.out.printf("listed %d file(s) beside %d unrelated ones in %d ms%n",
                found.size(), NOISE_FILES, millis);

        assertEquals(1, found.size(), "only the real log should be listed");
        assertTrue(millis < BUDGET_MS,
                "listing took " + millis + " ms, budget is " + BUDGET_MS + " ms — "
                        + "this runs on the game thread through tab-completion");
    }

    /**
     * The name check happens before resolution, but it must still happen after
     * it: a symlink named like a log whose target is not one must not slip
     * through the faster path.
     */
    @Test
    void the_faster_path_still_rejects_a_symlink_pointing_outside(@TempDir Path root) throws IOException {
        Path game = Files.createDirectories(root.resolve("game"));
        Path logs = Files.createDirectories(game.resolve("logs"));

        Path secret = root.resolve("secret.txt");
        Files.writeString(secret, "not a log");

        try {
            Files.createSymbolicLink(logs.resolve("latest.log"), secret);
        } catch (UnsupportedOperationException | IOException e) {
            return; // No symlink permission on this machine; covered on CI.
        }

        assertTrue(LogFiles.forGameDirectory(game).find("latest.log").isEmpty(),
                "a symlink escaping the log directory must not be shareable");
    }
}
