package day.alacraft.alalogger.history;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import day.alacraft.alalogger.AlaLogger;
import day.alacraft.alalogger.PrivateFiles;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * What this server has uploaded, kept on disk so a restart does not lose it.
 *
 * <p>Kept in a field, this information dies with the process: restart the server
 * and the delete tokens are gone, so the log uploaded five minutes before the
 * crash can never be taken down. Persisting it is the feature; everything else in
 * this class exists to make the persistence trustworthy.
 *
 * <p><b>Written atomically.</b> A new file is composed beside the old one and
 * moved over it, so a server killed mid-write finds either the previous history
 * or the new one, never half of each. The obvious alternative — truncate and
 * rewrite in place — loses every token in the file if the process dies during
 * the write, and the process dying unexpectedly is the exact situation this
 * class is here for.
 *
 * <p><b>Kept private where the filesystem allows it.</b> The tokens are
 * credentials. On POSIX the file is created {@code 0600} before anything is
 * written into it; on Windows it is given an owner-only ACL the moment it
 * exists and before it holds a token, which replaces the config directory's
 * inherited one. See {@link day.alacraft.alalogger.PrivateFiles}.
 *
 * <p><b>Bounded.</b> Only the most recent entries are kept, because an
 * unbounded file on a server that uploads a log per restart grows forever to
 * hold tokens for logs the site expired months ago.
 *
 * <p>Every public method is synchronised: uploads are performed off the main
 * thread so two of them can finish at once, and two concurrent saves would race
 * over the same temporary file.
 */
public final class UploadHistory {

    /**
     * How many uploads to remember.
     *
     * <p>Comfortably more than a person will ever scroll back through, and far
     * less than the number of restarts a server accumulates in a year. Logs
     * expire from the site after 90 days anyway, so older tokens are dead weight.
     */
    public static final int DEFAULT_MAX_ENTRIES = 50;

    /** Bumped only when a field changes meaning, as in {@code Config}. */
    private static final int SCHEMA_VERSION = 1;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static final String OWNER_ONLY = "rw-------";

    private static final Comparator<UploadRecord> NEWEST_FIRST =
            Comparator.comparing(UploadRecord::uploadedAt).reversed();

    private final Path file;
    private final int maxEntries;

    /** Newest first, so {@link #last()} and {@link #recent(int)} are a prefix. */
    private final List<UploadRecord> entries = new ArrayList<>();

    public UploadHistory(Path file) {
        this(file, DEFAULT_MAX_ENTRIES);
    }

    public UploadHistory(Path file, int maxEntries) {
        this.file = Objects.requireNonNull(file, "file");
        this.maxEntries = Math.max(1, maxEntries);
        load();
    }

    /** Records an upload and writes the history out. */
    public synchronized UploadRecord add(String id, String url, String token, String fileName) {
        UploadRecord record = new UploadRecord(id, url, token, Instant.now(), fileName);
        add(record);
        return record;
    }

    /** Records an already-built entry, replacing any earlier one with the same id. */
    public synchronized void add(UploadRecord record) {
        Objects.requireNonNull(record, "record");

        // Re-uploading the same file produces a new id, but a caller replaying a
        // response (a retry that actually succeeded the first time) would
        // otherwise leave two entries and two chances to use a stale token.
        entries.removeIf(existing -> existing.id().equals(record.id()));
        entries.add(0, record);
        // Inserting at the front is right for the normal case, where the record
        // was stamped a moment ago. Sorting keeps the in-memory order identical
        // to the order the file reloads in, so `last()` cannot mean one thing
        // before a restart and another after it. The sort is stable, so a record
        // added with a timestamp equal to an existing one stays in front of it.
        entries.sort(NEWEST_FIRST);
        trim();
        save();
    }

    /** The most recent uploads, newest first, at most {@code count} of them. */
    public synchronized List<UploadRecord> recent(int count) {
        if (count <= 0) {
            return List.of();
        }
        return List.copyOf(entries.subList(0, Math.min(count, entries.size())));
    }

    /** Looks up an upload by the id printed in chat. */
    public synchronized Optional<UploadRecord> findById(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        return entries.stream().filter(record -> record.id().equals(id)).findFirst();
    }

    /** The most recent upload, which is what {@code /alog delete last} means. */
    public synchronized Optional<UploadRecord> last() {
        return entries.isEmpty() ? Optional.empty() : Optional.of(entries.get(0));
    }

    /**
     * Forgets an upload, after the site confirmed the deletion.
     *
     * @return whether there was anything to forget
     */
    public synchronized boolean remove(String id) {
        if (id == null || id.isBlank()) {
            return false;
        }

        if (!entries.removeIf(record -> record.id().equals(id))) {
            return false;
        }

        save();
        return true;
    }

    public synchronized int size() {
        return entries.size();
    }

    /** Where the history is stored. Diagnostics and tests. */
    public Path file() {
        return file;
    }

    private void load() {
        if (!Files.exists(file)) {
            return;
        }

        try {
            JsonElement root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
            JsonArray array = arrayOf(root);

            for (JsonElement element : array) {
                // One malformed entry — a hand-edit, a half-written file from a
                // version of this class that did not write atomically — must not
                // cost the player the other forty-nine tokens.
                readRecord(element).ifPresent(entries::add);
            }

            entries.sort(NEWEST_FIRST);
            trim();
        } catch (Exception e) {
            // Same posture as Config: a broken file never stops a server, and it
            // is left on disk rather than deleted so it can still be inspected.
            // The next successful upload will overwrite it.
            AlaLogger.LOGGER.warn("Could not read the upload history at {} ({}). Starting with an empty one.",
                    file, e.getMessage());
            entries.clear();
        }
    }

    private void save() {
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");

        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }

            createPrivate(temporary);
            Files.writeString(temporary, GSON.toJson(document()) + System.lineSeparator(),
                    StandardCharsets.UTF_8, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);

            try {
                Files.move(temporary, file,
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                // Some network and container filesystems refuse an atomic rename.
                // A plain replace is still a great deal better than writing in
                // place, since the content was fully written before the move.
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            // A history that cannot be written must not fail the upload that
            // produced it: the link is already in the player's hands and is the
            // thing they asked for. Losing the delete token is worth a warning,
            // not an exception.
            AlaLogger.LOGGER.warn("Could not write the upload history to {} ({}). "
                    + "Delete tokens from this session will not survive a restart.", file, e.getMessage());
            deleteQuietly(temporary);
        }
    }

    /**
     * Creates the temporary file with owner-only permissions <em>before</em> it
     * holds anything, so there is no moment where the tokens exist in a
     * world-readable file. The permissions travel with the file through the
     * rename, which is why nothing needs to be re-applied afterwards.
     *
     * <p>POSIX can do this in the create call itself. Windows cannot, so the
     * file is created and tightened immediately after — while it is still
     * empty, which is the same guarantee by a slower route.
     */
    private static void createPrivate(Path path) throws IOException {
        Files.deleteIfExists(path);

        if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            Files.createFile(path, PosixFilePermissions.asFileAttribute(
                    PosixFilePermissions.fromString(OWNER_ONLY)));
        } else {
            Files.createFile(path);
            PrivateFiles.restrictToOwner(path);
        }
    }

    private JsonObject document() {
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", SCHEMA_VERSION);

        JsonArray array = new JsonArray();
        for (UploadRecord record : entries) {
            JsonObject json = new JsonObject();
            json.addProperty("id", record.id());
            json.addProperty("url", record.url());
            json.addProperty("token", record.token());
            // ISO-8601 rather than epoch millis: the file is meant to be readable
            // by the person whose tokens are in it.
            json.addProperty("uploadedAt", record.uploadedAt().toString());
            json.addProperty("fileName", record.fileName());
            array.add(json);
        }
        root.add("entries", array);

        return root;
    }

    private static JsonArray arrayOf(JsonElement root) {
        if (root != null && root.isJsonArray()) {
            return root.getAsJsonArray();
        }
        if (root != null && root.isJsonObject() && root.getAsJsonObject().has("entries")
                && root.getAsJsonObject().get("entries").isJsonArray()) {
            return root.getAsJsonObject().getAsJsonArray("entries");
        }
        return new JsonArray();
    }

    private static Optional<UploadRecord> readRecord(JsonElement element) {
        if (element == null || !element.isJsonObject()) {
            return Optional.empty();
        }

        JsonObject json = element.getAsJsonObject();
        String id = string(json, "id");
        if (id.isBlank()) {
            return Optional.empty();
        }

        return Optional.of(new UploadRecord(
                id,
                string(json, "url"),
                string(json, "token"),
                instant(json, "uploadedAt"),
                string(json, "fileName")));
    }

    private static String string(JsonObject json, String key) {
        return json.has(key) && json.get(key).isJsonPrimitive() ? json.get(key).getAsString() : "";
    }

    private static Instant instant(JsonObject json, String key) {
        String raw = string(json, key);

        try {
            return Instant.parse(raw);
        } catch (DateTimeParseException e) {
            // An unreadable timestamp only affects ordering and display, so the
            // entry keeps its token and sorts to the bottom rather than being
            // thrown away over a date.
            return Instant.EPOCH;
        }
    }

    private void trim() {
        while (entries.size() > maxEntries) {
            entries.remove(entries.size() - 1);
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            AlaLogger.LOGGER.debug("Could not remove {}: {}", path, e.toString());
        }
    }
}
