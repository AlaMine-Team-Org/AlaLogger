package day.alacraft.alalogger;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import day.alacraft.alalogger.logs.LogFile;
import day.alacraft.alalogger.logs.LogFileType;
import day.alacraft.alalogger.logs.LogFiles;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Notices crash files that appeared since the last start, and offers them.
 *
 * <p>Offers. It never uploads anything on its own: a crash report is the
 * player's data, and the one rule this mod does not bend is that a log leaves
 * the machine only when a human asks. What this class removes is the other half
 * of the problem — that after a crash nobody remembers the filename, and the
 * evidence quietly ages out of the folder.
 *
 * <p>Each file is offered exactly once. The alternative, offering whatever is
 * new since some timestamp, turns into a wall of text on the first start after
 * a bad night, and then repeats it every restart until someone acts.
 */
public final class CrashWatch {

    /**
     * How far back to look on the very first run.
     *
     * <p>Without this, installing the mod on a server that has been crashing for
     * a year greets the admin with a list of last year's crashes. A week is long
     * enough to catch "it died last night", short enough not to be archaeology.
     */
    private static final Duration FIRST_RUN_WINDOW = Duration.ofDays(7);

    /** More than this and we print a count instead of a list. */
    private static final int MAX_LISTED = 5;

    /**
     * Bumped to 2 when {@code seen} changed from file names to full paths.
     *
     * <p>A marker written by an older version is not readable as this one: its
     * names would match nothing, and with the first-run window already closed
     * that would replay every crash file on the disk. It is therefore treated as
     * a first run, which re-applies the window.
     */
    private static final int SCHEMA_VERSION = 2;

    private final Path markerFile;
    private final LogFiles files;

    public CrashWatch(Path markerFile, LogFiles files) {
        this.markerFile = markerFile;
        this.files = files;
    }

    /**
     * Crash files worth telling the operator about, newest first.
     *
     * <p>Marks everything it returns as seen before returning, so a caller that
     * crashes while printing does not produce the same list forever.
     */
    public List<LogFile> unreported() {
        Marker marker = readMarker();
        Instant cutoff = marker.firstRun()
                ? Instant.now().minus(FIRST_RUN_WINDOW)
                : Instant.EPOCH;

        List<LogFile> fresh = new ArrayList<>();

        for (LogFile file : files.list()) {
            if (file.type() != LogFileType.CRASH_REPORT && file.type() != LogFileType.JVM_CRASH) {
                continue;
            }
            if (marker.seen().contains(key(file))) {
                continue;
            }
            if (file.modified().isBefore(cutoff)) {
                continue;
            }

            fresh.add(file);
        }

        if (!fresh.isEmpty() || marker.firstRun()) {
            Set<String> seen = new LinkedHashSet<>(marker.seen());
            fresh.forEach(file -> seen.add(key(file)));
            writeMarker(seen);
        }

        return fresh;
    }

    /**
     * What identifies a crash file across restarts.
     *
     * <p>The resolved path, not the name. HotSpot writes {@code hs_err_pid<PID>.log}
     * into the working directory or into {@code java.io.tmpdir}, and pids are
     * reused, so two different crashes can arrive under one name from two
     * directories at once. Keyed by name, the second one is silently never
     * offered — and the one in tmpdir is usually the interesting half, because it
     * is where the JVM lands when it could not write beside the game.
     *
     * <p>{@link LogFile#path()} is already resolved through {@code toRealPath()},
     * so two spellings of the same file cannot produce two keys.
     */
    private static String key(LogFile file) {
        return file.path().toString();
    }

    /** How many of a list to print before summarising the rest. */
    public static int maxListed() {
        return MAX_LISTED;
    }

    private Marker readMarker() {
        if (!Files.exists(markerFile)) {
            return new Marker(Set.of(), true);
        }

        try {
            JsonObject json = JsonParser.parseString(
                    Files.readString(markerFile, StandardCharsets.UTF_8)).getAsJsonObject();

            if (schemaVersion(json) != SCHEMA_VERSION) {
                // Same posture as a damaged marker: what it says cannot be
                // trusted to mean what this version means by it.
                AlaLogger.LOGGER.debug("{} was written by another schema version; treating this as a first run.",
                        markerFile.getFileName());

                return new Marker(Set.of(), true);
            }

            Set<String> seen = new LinkedHashSet<>();
            if (json.has("seen") && json.get("seen").isJsonArray()) {
                for (var element : json.getAsJsonArray("seen")) {
                    if (element.isJsonPrimitive()) {
                        seen.add(element.getAsString());
                    }
                }
            }

            return new Marker(seen, false);
        } catch (Exception e) {
            // A damaged marker must not turn into a wall of old crashes, so it
            // is treated as a first run — which re-applies the time window.
            AlaLogger.LOGGER.warn("Could not read {} ({}); treating this as a first run.",
                    markerFile.getFileName(), e.getMessage());

            return new Marker(Set.of(), true);
        }
    }

    private void writeMarker(Set<String> seen) {
        // Keep the list from growing without bound on a server that crashes
        // often: only the newest entries can still match a file on disk.
        List<String> trimmed = new ArrayList<>(seen);
        if (trimmed.size() > 200) {
            trimmed = trimmed.subList(trimmed.size() - 200, trimmed.size());
        }

        JsonObject json = new JsonObject();
        json.addProperty("schemaVersion", SCHEMA_VERSION);
        JsonArray array = new JsonArray();
        trimmed.forEach(array::add);
        json.add("seen", array);

        // Composed beside the marker and moved over it, the way the upload
        // history is written. A process killed halfway through a plain write
        // leaves a truncated file, which readMarker() can only treat as a first
        // run — and a first run after a bad night is a week of old crash reports
        // offered a second time. The move is the one step that cannot be seen
        // half-finished.
        Path temporary = markerFile.resolveSibling(markerFile.getFileName() + ".tmp");

        try {
            Path parent = markerFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }

            Files.writeString(temporary, json.toString(), StandardCharsets.UTF_8);

            try {
                Files.move(temporary, markerFile,
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                // Some network and container filesystems refuse an atomic rename.
                // A plain replace still beats writing in place: the content was
                // complete before the move started.
                Files.move(temporary, markerFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            // Not fatal: the cost is offering the same file again next start.
            AlaLogger.LOGGER.warn("Could not write {} ({}).", markerFile.getFileName(), e.getMessage());
            deleteQuietly(temporary);
        }
    }

    private static int schemaVersion(JsonObject json) {
        return json.has("schemaVersion") && json.get("schemaVersion").isJsonPrimitive()
                ? json.get("schemaVersion").getAsInt()
                : 0;
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            AlaLogger.LOGGER.debug("Could not remove {}: {}", path, e.toString());
        }
    }

    private record Marker(Set<String> seen, boolean firstRun) {
    }
}
