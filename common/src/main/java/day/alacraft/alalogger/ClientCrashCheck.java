package day.alacraft.alalogger;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import day.alacraft.alalogger.logs.LogFile;
import day.alacraft.alalogger.logs.LogFileType;
import day.alacraft.alalogger.logs.LogFiles;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Decides, when the game starts, what to offer a player whose last session crashed.
 *
 * <p>Offer, never upload: this only chooses files. A screen shows them and a file
 * leaves the machine when the player clicks.
 *
 * <p>What counts as the last session's crash:
 * <ul>
 *   <li>a crash report or JVM fatal error log the {@link CrashWatch} has not
 *       reported yet;</li>
 *   <li>but a JVM log in the shared temporary directory only when it carries the
 *       last session's process id. {@code %TEMP%} is shared by every Java program
 *       and every instance on the machine, so without the id another instance's
 *       crash - or anything named {@code hs_err_pid*.log} - would be offered as ours;</li>
 *   <li>and when the last session ended without a clean exit and left no file at
 *       all (killed, a frozen driver), the log of that session, which the game
 *       rotated into {@code logs/*.log.gz} on this start.</li>
 * </ul>
 *
 * <p>A session is "clean" when {@link #stopped()} ran. The session file is written
 * on every start, so the next start can tell.
 */
public final class ClientCrashCheck {

    private final Path sessionFile;
    private final CrashWatch watch;
    private final LogFiles files;
    private final Path temporaryDirectory;

    public ClientCrashCheck(Path sessionFile, CrashWatch watch, LogFiles files, Path temporaryDirectory) {
        this.sessionFile = sessionFile;
        this.watch = watch;
        this.files = files;
        this.temporaryDirectory = temporaryDirectory;
    }

    /** What to offer, and whether the last session ended without a clean exit. */
    public record Offer(List<LogFile> files, boolean abnormalExit) {

        public Offer {
            files = List.copyOf(files);
        }

        public boolean isEmpty() {
            return files.isEmpty();
        }
    }

    /**
     * Records this session and returns what the previous one left behind.
     *
     * <p>Reads the disk; call it off the game thread.
     */
    public Offer start(long pid, Instant now) {
        Optional<Session> previous = readSession();
        writeSession(new Session(pid, now, false));

        boolean abnormal = previous.isPresent() && !previous.get().cleanExit();
        Long previousPid = previous.map(Session::pid).orElse(null);

        List<LogFile> offered = new ArrayList<>();
        for (LogFile file : watch.pending()) {
            if (file.type() == LogFileType.JVM_CRASH && inTemporaryDirectory(file)
                    && !namesProcess(file, previousPid)) {
                continue;
            }
            offered.add(file);
        }

        if (offered.isEmpty() && abnormal) {
            previousLog(previous.get().startedAt()).ifPresent(offered::add);
        }

        return new Offer(offered, abnormal);
    }

    /** The files were on screen: do not offer them again. */
    public void shown(Offer offer) {
        watch.markShown(offer.files());
    }

    /** The game is closing normally. */
    public void stopped() {
        readSession().ifPresent(session -> writeSession(new Session(session.pid(), session.startedAt(), true)));
    }

    private boolean inTemporaryDirectory(LogFile file) {
        if (temporaryDirectory == null) {
            return false;
        }
        try {
            return temporaryDirectory.toRealPath().equals(file.path().getParent());
        } catch (IOException | SecurityException e) {
            return false;
        }
    }

    private static boolean namesProcess(LogFile file, Long pid) {
        return pid != null && file.name().toLowerCase(Locale.ROOT).contains("pid" + pid + ".");
    }

    /**
     * The newest rotated log written since the last session started. The game
     * renames {@code latest.log} to {@code <date>-<n>.log.gz} on startup, before
     * any mod runs, so by now the last session's log has that shape.
     */
    private Optional<LogFile> previousLog(Instant previousStart) {
        return files.list().stream()
                .filter(file -> file.type() == LogFileType.LOG)
                .filter(file -> file.name().toLowerCase(Locale.ROOT).endsWith(".log.gz"))
                .filter(file -> !file.name().toLowerCase(Locale.ROOT).startsWith("debug"))
                .filter(file -> !file.modified().isBefore(previousStart))
                .max(Comparator.comparing(LogFile::modified));
    }

    private record Session(long pid, Instant startedAt, boolean cleanExit) {
    }

    private Optional<Session> readSession() {
        if (!Files.exists(sessionFile)) {
            return Optional.empty();
        }
        try {
            JsonObject json = JsonParser.parseString(
                    Files.readString(sessionFile, StandardCharsets.UTF_8)).getAsJsonObject();

            return Optional.of(new Session(
                    json.get("pid").getAsLong(),
                    Instant.parse(json.get("startedAt").getAsString()),
                    json.get("cleanExit").getAsBoolean()));
        } catch (Exception e) {
            // A damaged file says nothing about how the last session ended.
            // Treating it as "no previous session" offers only real crash files,
            // never a guessed log.
            AlaLogger.LOGGER.debug("Could not read {} ({}).", sessionFile.getFileName(), e.getMessage());
            return Optional.empty();
        }
    }

    private void writeSession(Session session) {
        JsonObject json = new JsonObject();
        json.addProperty("pid", session.pid());
        json.addProperty("startedAt", session.startedAt().toString());
        json.addProperty("cleanExit", session.cleanExit());

        try {
            AtomicFiles.write(sessionFile, json.toString());
        } catch (IOException e) {
            // Not fatal: the cost is that the next start cannot tell how this one ended.
            AlaLogger.LOGGER.warn("Could not write {} ({}).", sessionFile.getFileName(), e.getMessage());
        }
    }
}
