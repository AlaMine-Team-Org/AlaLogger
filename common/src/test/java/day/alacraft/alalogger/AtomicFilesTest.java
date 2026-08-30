package day.alacraft.alalogger;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AtomicFilesTest {

    @TempDir
    Path directory;

    @Test
    void writesAndReadsBackUnchanged() throws IOException {
        Path file = directory.resolve("plain.json");

        AtomicFiles.write(file, "{\"seen\": []}");

        assertEquals("{\"seen\": []}", Files.readString(file, StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("UTF-8 regardless of what this machine defaults to")
    void writesUtf8() throws IOException {
        Path file = directory.resolve("text.json");

        AtomicFiles.write(file, "genügend");

        assertEquals("genügend", Files.readString(file, StandardCharsets.UTF_8));
    }

    @Test
    void createsTheDirectoriesItNeeds() throws IOException {
        Path file = directory.resolve("deep").resolve("deeper").resolve("history.json");

        AtomicFiles.writePrivate(file, "[]");

        assertTrue(Files.isRegularFile(file));
    }

    @Test
    void replacesWhatWasThere() throws IOException {
        Path file = directory.resolve("history.json");

        AtomicFiles.writePrivate(file, "first");
        AtomicFiles.writePrivate(file, "second");

        assertEquals("second", Files.readString(file, StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("the temporary file is never left lying around")
    void leavesNoTemporaryBehind() throws IOException {
        Path file = directory.resolve("history.json");

        AtomicFiles.writePrivate(file, "[]");

        assertFalse(Files.exists(directory.resolve("history.json.tmp")));

        // Including when the write cannot be finished. A directory where the
        // caller asked for a file is the cheapest way to make the move fail
        // without breaking the filesystem underneath the test.
        Path blocked = directory.resolve("blocked.json");
        Files.createDirectory(blocked);

        assertThrows(IOException.class, () -> AtomicFiles.write(blocked, "anything"));
        assertFalse(Files.exists(directory.resolve("blocked.json.tmp")),
                "a failed write must not leave its scratch file behind");
    }

    @Test
    @DisplayName("a concurrent reader does not cost the write")
    void survivesAConcurrentReader() throws Exception {
        // Weaker than it looks, and worth saying so. Windows refuses to replace
        // a file some other handle has open *without* FILE_SHARE_DELETE, which
        // is how a real-time virus scanner holds one — measured under one,
        // nineteen writes in twenty were lost, each a delete token that silently
        // stopped existing. That is what MOVE_ATTEMPTS in AtomicFiles is for.
        //
        // This test cannot produce that condition: every handle the JDK opens
        // includes FILE_SHARE_DELETE, so the rename below succeeds on the first
        // attempt whether the retry exists or not — confirmed by setting
        // MOVE_ATTEMPTS to 1, which changes nothing here. The retry is therefore
        // defensive and rests on a measurement made with an external holder, not
        // on this. What this does hold shut is the other direction: that the mod
        // never opens its own target in a way that makes a reader fatal.
        Path file = directory.resolve("history.json");
        AtomicFiles.writePrivate(file, "first");

        FileChannel held = FileChannel.open(file, StandardOpenOption.READ);
        Thread release = new Thread(() -> {
            try {
                Thread.sleep(25);
                held.close();
            } catch (Exception ignored) {
                // The write below is the assertion; this thread only lets go.
            }
        });

        release.start();
        AtomicFiles.writePrivate(file, "second");
        release.join();
        held.close();

        assertEquals("second", Files.readString(file, StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("a file symlinked elsewhere is written through, not detached")
    void followsASymlink() throws IOException {
        // Panel hosts and containers link a config or a data file into a shared
        // location. Writing in place honoured that; a rename over the link would
        // replace it with a regular file, and the operator's shared original
        // would silently stop being the file the mod reads.
        Path real = directory.resolve("shared").resolve("history.json");
        Files.createDirectories(real.getParent());
        Files.writeString(real, "before", StandardCharsets.UTF_8);

        Path link = directory.resolve("config").resolve("history.json");
        Files.createDirectories(link.getParent());

        try {
            Files.createSymbolicLink(link, real);
        } catch (IOException | UnsupportedOperationException e) {
            Assumptions.abort("this machine will not create symlinks: " + e);
        }

        AtomicFiles.writePrivate(link, "after");

        assertTrue(Files.isSymbolicLink(link), "the link itself must survive the write");
        assertEquals("after", Files.readString(real, StandardCharsets.UTF_8),
                "and the content must land on what it points at");
    }

    @Test
    @DisplayName("a file written privately is owner-only from the moment it exists")
    void writesPrivately() throws IOException {
        Path file = directory.resolve("tokens.json");

        AtomicFiles.writePrivate(file, "[{\"token\": \"secret\"}]");

        if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
            return;
        }

        AclFileAttributeView view = Files.getFileAttributeView(file, AclFileAttributeView.class);
        assertNotNull(view, "no filesystem view can express who may read this file");
        List<UserPrincipal> allowed = view.getAcl().stream().map(AclEntry::principal).distinct().toList();
        assertEquals(List.of(Files.getOwner(file)), allowed);
    }
}
