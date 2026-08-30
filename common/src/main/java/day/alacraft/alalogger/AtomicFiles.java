package day.alacraft.alalogger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;

/**
 * Writing a file so that a process killed halfway through leaves the old one.
 *
 * <p>Everything this mod keeps on disk is written while something is going
 * wrong. The upload history is saved the moment a log is published, which is
 * usually seconds before whatever prompted the upload takes the server down;
 * the crash marker is written after a crash was found. The naive write —
 * truncate the file, then fill it — has a window in which the file exists and
 * is empty, and the machines this runs on are exactly the ones that stop
 * inside that window.
 *
 * <p>So the content is composed beside the target and moved over it. The move
 * is the one step a filesystem will not show half-finished, so a reader sees
 * either the previous file or the new one and never a truncated mixture.
 *
 * <p>Three files needed this and had two and a half implementations of it: the
 * history wrote atomically and privately, the crash marker atomically, the
 * config neither — it wrote {@code alalogger.json} in place and tightened the
 * permissions afterwards, leaving a moment where a file containing an API
 * token was readable by every account on the machine.
 */
public final class AtomicFiles {

    private static final String OWNER_ONLY = "rw-------";

    private AtomicFiles() {
    }

    /** Replaces {@code file} with {@code content}, atomically. */
    public static void write(Path file, String content) throws IOException {
        write(file, content, false);
    }

    /**
     * The same, for a file that holds credentials.
     *
     * <p>The permissions are set on the temporary file <em>before</em> anything
     * is written into it and travel with it through the rename, so there is no
     * instant at which the secret exists in a world-readable file. Tightening
     * afterwards would leave exactly that instant.
     */
    public static void writePrivate(Path file, String content) throws IOException {
        write(file, content, true);
    }

    /**
     * How many times to attempt the rename before giving up.
     *
     * <p>Not for the filesystem's benefit but for the other programs on the
     * machine. Windows refuses to replace a file another handle has open, and a
     * real-time virus scanner opening {@code history.json} the instant it is
     * written is ordinary behaviour, not an edge case: under one, nineteen
     * writes in twenty were lost, and each of those was a delete token that
     * quietly stopped existing. The handle is gone in milliseconds.
     */
    private static final int MOVE_ATTEMPTS = 4;

    private static void write(Path file, String content, boolean ownerOnly) throws IOException {
        Path target = followLink(file);
        Path parent = target.getParent();

        if (parent != null) {
            Files.createDirectories(parent);
        }

        // Beside the target rather than in the system temp directory: a move
        // across filesystems is a copy, and a copy cannot be atomic.
        //
        // One name per target, deliberately, rather than a unique one per write.
        // Reusing it is what clears away the scratch file a process killed
        // mid-write left behind: the next write takes the same name and consumes
        // it. A unique name would leave that litter next to a credentials file
        // for ever. The cost is that two writers of the same path must not run
        // at once - which is why UploadHistory is synchronised, and why nothing
        // builds a second one on the same file.
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");

        try {
            if (ownerOnly) {
                createPrivate(temporary);
            }

            Files.writeString(temporary, content, StandardCharsets.UTF_8);
            moveOnto(temporary, target);
        } catch (IOException e) {
            deleteQuietly(temporary);
            throw e;
        }
    }

    /** The rename, retried while something else is holding the target open. */
    private static void moveOnto(Path temporary, Path target) throws IOException {
        AccessDeniedException refused = null;

        for (int attempt = 0; attempt < MOVE_ATTEMPTS; attempt++) {
            try {
                try {
                    Files.move(temporary, target,
                            StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    // Some network and container filesystems refuse an atomic
                    // rename. A plain replace still beats writing in place: the
                    // content was complete before the move started.
                    Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
                }

                return;
            } catch (AccessDeniedException e) {
                refused = e;

                try {
                    Thread.sleep(20L * (attempt + 1));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }

        throw refused;
    }

    /**
     * Creates the scratch file empty and readable only by its owner.
     *
     * <p>The permissions are part of the creation on POSIX, so the file is never
     * readable by anyone else even for an instant, and they travel with it
     * through the rename. Windows cannot express that in one call, so the file
     * is created and tightened while it is still empty — the same guarantee by a
     * slower route. See {@link PrivateFiles} for why the Windows half replaces
     * the inherited ACL rather than adding to it.
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

    /**
     * Where the content actually has to land.
     *
     * <p>A rename replaces a symlink with a regular file, which would quietly
     * detach a config or a history file that somebody linked into a shared
     * location — an ordinary arrangement under Docker and on panel hosts, and
     * one that writing in place used to honour. Following the link first keeps
     * that setup working and keeps the write atomic.
     *
     * <p>A link pointing at nothing is left to be replaced: there is no target
     * to write through, and refusing to save at all would be the worse answer.
     */
    private static Path followLink(Path file) {
        try {
            return Files.isSymbolicLink(file) ? file.toRealPath() : file;
        } catch (IOException | SecurityException e) {
            return file;
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
