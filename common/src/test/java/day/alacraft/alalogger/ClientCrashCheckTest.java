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
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a player is offered on the title screen after a crash. Every case here is a
 * way to offer the wrong file or none at all; each was reasoned from where the
 * game, the JVM and the launchers actually put things.
 */
// @implements TC-LOG-015
class ClientCrashCheckTest {

    @TempDir
    Path root;

    private Path game;
    private Path temp;

    private ClientCrashCheck check() throws IOException {
        game = Files.createDirectories(root.resolve("game"));
        temp = Files.createDirectories(root.resolve("tmp"));
        LogFiles files = new LogFiles(game, List.of(game, temp), null);
        Path data = game.resolve("config/alalogger");
        return new ClientCrashCheck(data.resolve("session.json"),
                new CrashWatch(data.resolve("crash-marker.json"), files), files, temp);
    }

    private static Path write(Path directory, String name, Instant modified) throws IOException {
        Files.createDirectories(directory);
        Path file = directory.resolve(name);
        Files.writeString(file, "x\n", StandardCharsets.UTF_8);
        Files.setLastModifiedTime(file, FileTime.from(modified));
        return file;
    }

    private static List<String> names(ClientCrashCheck.Offer offer) {
        return offer.files().stream().map(LogFile::name).toList();
    }

    @Test
    void aCrashReportFromTheLastSessionIsOfferedOnce() throws IOException {
        ClientCrashCheck check = check();
        Instant t0 = Instant.now().minusSeconds(600);

        assertTrue(check.start(100, t0).isEmpty(), "nothing to offer on the very first start");

        write(game.resolve("crash-reports"), "crash-client.txt", t0.plusSeconds(60));
        ClientCrashCheck.Offer offer = check.start(200, t0.plusSeconds(120));

        assertEquals(List.of("crash-client.txt"), names(offer));
        assertTrue(offer.abnormalExit(), "the first session never stopped cleanly");

        check.shown(offer);
        assertTrue(check.start(300, t0.plusSeconds(180)).isEmpty(), "shown once, never again");
    }

    @Test
    void aFileIsNotSpentUntilItWasShown() throws IOException {
        ClientCrashCheck check = check();
        Instant t0 = Instant.now().minusSeconds(600);
        check.start(100, t0);
        write(game.resolve("crash-reports"), "crash-client.txt", t0.plusSeconds(60));

        // The game closed before the title screen drew the offer: still owed.
        check.start(200, t0.plusSeconds(120));
        assertEquals(List.of("crash-client.txt"), names(check.start(300, t0.plusSeconds(180))));
    }

    @Test
    void aJvmCrashInTheSharedTempDirectoryMustCarryTheLastSessionsPid() throws IOException {
        ClientCrashCheck check = check();
        Instant t0 = Instant.now().minusSeconds(600);
        check.start(4242, t0);

        write(temp, "hs_err_pid4242.log", t0.plusSeconds(30));
        write(temp, "hs_err_pid9999.log", t0.plusSeconds(40)); // another program's crash

        assertEquals(List.of("hs_err_pid4242.log"), names(check.start(5000, t0.plusSeconds(120))));
    }

    @Test
    void aJvmCrashInTheGameDirectoryIsOursWhateverItsPid() throws IOException {
        ClientCrashCheck check = check();
        Instant t0 = Instant.now().minusSeconds(600);
        check.start(4242, t0);

        write(game, "hs_err_pid1.log", t0.plusSeconds(30));

        assertEquals(List.of("hs_err_pid1.log"), names(check.start(5000, t0.plusSeconds(120))));
    }

    @Test
    void aSessionThatDiedWithoutAFileOffersItsOwnLog() throws IOException {
        ClientCrashCheck check = check();
        Instant t0 = Instant.now().minusSeconds(600);

        write(game.resolve("logs"), "2026-11-07-1.log.gz", t0.minusSeconds(3600)); // an older session
        check.start(100, t0);
        write(game.resolve("logs"), "2026-11-07-2.log.gz", t0.plusSeconds(300)); // rotated on this start
        write(game.resolve("logs"), "debug-1.log.gz", t0.plusSeconds(300));

        ClientCrashCheck.Offer offer = check.start(200, t0.plusSeconds(400));

        assertTrue(offer.abnormalExit());
        assertEquals(List.of("2026-11-07-2.log.gz"), names(offer));
    }

    @Test
    void aCleanExitOffersNoLog() throws IOException {
        ClientCrashCheck check = check();
        Instant t0 = Instant.now().minusSeconds(600);

        check.start(100, t0);
        check.stopped();
        write(game.resolve("logs"), "2026-11-07-2.log.gz", t0.plusSeconds(300));

        ClientCrashCheck.Offer offer = check.start(200, t0.plusSeconds(400));

        assertFalse(offer.abnormalExit());
        assertTrue(offer.isEmpty());
    }

    @Test
    void aDamagedSessionFileOffersOnlyRealCrashFiles() throws IOException {
        ClientCrashCheck check = check();
        Instant t0 = Instant.now().minusSeconds(600);
        Path session = game.resolve("config/alalogger/session.json");
        Files.createDirectories(session.getParent());
        Files.writeString(session, "{ not json", StandardCharsets.UTF_8);
        write(game.resolve("logs"), "2026-11-07-2.log.gz", t0);

        ClientCrashCheck.Offer offer = check.start(200, t0.plusSeconds(60));

        assertFalse(offer.abnormalExit(), "a broken file says nothing about how the session ended");
        assertTrue(offer.isEmpty());
    }
}
