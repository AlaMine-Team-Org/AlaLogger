package day.alacraft.alalogger.logs;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;

/**
 * One file we are allowed to upload, with everything {@code /alog list} needs to
 * describe it without touching the disk again.
 *
 * <p>Size and modification time are captured at discovery on purpose. Listing
 * twenty files means twenty {@code stat} calls either way; doing them once, up
 * front, means the command layer can sort, filter and format without a single
 * further IO call — and, more importantly, that the list a player sees is
 * internally consistent instead of a mix of readings taken milliseconds apart
 * from a directory the server is actively writing to.
 *
 * @param path     the resolved, symlink-free path — the traversal check in
 *                 {@link LogFiles} has already passed for it
 * @param name     the file name as it should be shown and typed back into a command
 * @param type     what kind of file this is, which decides {@link #readMode()}
 * @param size     size in bytes on disk (for {@code .gz} files, the compressed size)
 * @param modified last modification time, the basis of both sorting and the
 *                 "5 minutes ago" that makes a list of near-identical rotated
 *                 names actually pickable
 */
public record LogFile(Path path, String name, LogFileType type, long size, Instant modified) {

    public LogFile {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(modified, "modified");
    }

    /**
     * Whether the file is a rotated, gzipped log.
     *
     * <p>Only a hint for callers; {@link LogReader} decides by looking at the
     * actual first bytes, because a mis-named file should still be readable.
     */
    public boolean isCompressed() {
        return name.toLowerCase(Locale.ROOT).endsWith(".gz");
    }

    /** Which end of this file to keep when it is larger than the upload limits. */
    public ReadMode readMode() {
        return type.readMode();
    }

    /** How old the file is, for the "(2.4 MB, 5 min ago)" half of a list entry. */
    public Duration age(Instant now) {
        Duration age = Duration.between(modified, now);
        // A file stamped in the future — a clock change, or a network share with
        // a skewed clock — would otherwise print as a negative age.
        return age.isNegative() ? Duration.ZERO : age;
    }
}
