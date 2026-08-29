package day.alacraft.alalogger.history;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UploadHistoryTest {

    @TempDir
    Path directory;

    private Path file() {
        return directory.resolve("alalogger").resolve("history.json");
    }

    @Test
    void remembersAnUpload() {
        UploadHistory history = new UploadHistory(file());

        UploadRecord record = history.add("AbCd2345", "https://alacraft.day/ru/logs/AbCd2345",
                "delete-token", "latest.log");

        assertEquals(1, history.size());
        assertEquals(record, history.last().orElseThrow());
        assertEquals(record, history.findById("AbCd2345").orElseThrow());
        assertTrue(history.findById("nope").isEmpty());
        assertTrue(record.hasToken());
    }

    @Test
    @DisplayName("the delete token survives a restart - the whole reason this class exists")
    void survivesARestart() {
        UploadHistory before = new UploadHistory(file());
        before.add("AbCd2345", "https://alacraft.day/en/logs/AbCd2345", "delete-token", "latest.log");
        before.add("EfGh6789", "https://alacraft.day/en/logs/EfGh6789", "another-token", "crash.txt");

        // A new object over the same file is exactly what a server restart does.
        UploadHistory after = new UploadHistory(file());

        assertEquals(2, after.size());
        assertEquals("EfGh6789", after.last().orElseThrow().id());
        assertEquals("delete-token", after.findById("AbCd2345").orElseThrow().token());
        assertEquals("latest.log", after.findById("AbCd2345").orElseThrow().fileName());
        assertEquals("https://alacraft.day/en/logs/EfGh6789", after.findById("EfGh6789").orElseThrow().url());
    }

    @Test
    void keepsTheNewestEntriesOnly() {
        UploadHistory history = new UploadHistory(file(), 3);

        for (int i = 1; i <= 10; i++) {
            history.add(new UploadRecord("id" + i, "url" + i, "token" + i,
                    Instant.now().minus(10 - i, ChronoUnit.MINUTES), "file" + i));
        }

        assertEquals(3, history.size());
        assertEquals(List.of("id10", "id9", "id8"),
                history.recent(10).stream().map(UploadRecord::id).toList());
        assertEquals(3, new UploadHistory(file(), 3).size());
    }

    @Test
    void returnsTheMostRecentFirst() {
        UploadHistory history = new UploadHistory(file());
        Instant now = Instant.now();

        history.add(new UploadRecord("older", "u", "t", now.minus(2, ChronoUnit.HOURS), "a.log"));
        history.add(new UploadRecord("newest", "u", "t", now, "b.log"));
        history.add(new UploadRecord("middle", "u", "t", now.minus(1, ChronoUnit.HOURS), "c.log"));

        assertEquals("newest", history.last().orElseThrow().id());
        assertEquals(List.of("newest", "middle"),
                history.recent(2).stream().map(UploadRecord::id).toList());
        assertEquals(List.of(), history.recent(0));
        assertEquals(3, history.recent(99).size());
    }

    @Test
    void forgetsAnUpload() {
        UploadHistory history = new UploadHistory(file());
        history.add("AbCd2345", "url", "token", "latest.log");

        assertTrue(history.remove("AbCd2345"));
        assertFalse(history.remove("AbCd2345"));
        assertFalse(history.remove(null));
        assertEquals(0, history.size());
        assertEquals(0, new UploadHistory(file()).size());
    }

    @Test
    @DisplayName("re-recording the same id replaces it instead of leaving a stale token behind")
    void replacesDuplicateIds() {
        UploadHistory history = new UploadHistory(file());

        history.add("AbCd2345", "url", "first-token", "latest.log");
        history.add("AbCd2345", "url", "second-token", "latest.log");

        assertEquals(1, history.size());
        assertEquals("second-token", history.findById("AbCd2345").orElseThrow().token());
    }

    @Test
    @DisplayName("the write is atomic - no half-written file and no temporary left behind")
    void writesAtomically() throws IOException {
        UploadHistory history = new UploadHistory(file());

        for (int i = 1; i <= 5; i++) {
            history.add("id" + i, "url" + i, "token" + i, "file" + i);
        }

        try (Stream<Path> entries = Files.list(file().getParent())) {
            assertEquals(Set.of("history.json"),
                    entries.map(path -> path.getFileName().toString()).collect(Collectors.toSet()));
        }

        // Shrinking the history must replace the file, not overwrite its front and
        // leave the tail of the longer version behind as trailing garbage.
        for (int i = 1; i <= 4; i++) {
            history.remove("id" + i);
        }

        assertEquals(1, new UploadHistory(file()).size());
        assertTrue(Files.readString(file(), StandardCharsets.UTF_8).trim().endsWith("}"));
    }

    @Test
    @DisplayName("a temporary file left by a killed process does not block the next write")
    void overwritesAStaleTemporaryFile() throws IOException {
        Files.createDirectories(file().getParent());
        Files.writeString(file().resolveSibling("history.json.tmp"), "{ half written",
                StandardCharsets.UTF_8);

        UploadHistory history = new UploadHistory(file());
        history.add("AbCd2345", "url", "token", "latest.log");

        assertEquals(1, new UploadHistory(file()).size());
        assertFalse(Files.exists(file().resolveSibling("history.json.tmp")));
    }

    @Test
    @DisplayName("a corrupt file costs the tokens but never the server's startup")
    void toleratesACorruptFile() throws IOException {
        Files.createDirectories(file().getParent());
        Files.writeString(file(), "this is not json at all", StandardCharsets.UTF_8);

        UploadHistory history = new UploadHistory(file());

        assertEquals(0, history.size());

        history.add("AbCd2345", "url", "token", "latest.log");
        assertEquals(1, new UploadHistory(file()).size());
    }

    @Test
    @DisplayName("one broken entry does not take the other tokens down with it")
    void skipsBrokenEntries() throws IOException {
        Files.createDirectories(file().getParent());
        Files.writeString(file(), """
                {
                  "schemaVersion": 1,
                  "entries": [
                    {"id": "good1", "url": "u", "token": "t", "uploadedAt": "2026-08-28T10:00:00Z", "fileName": "a.log"},
                    {"url": "u", "token": "t"},
                    "not an object",
                    {"id": "good2", "url": "u", "token": "t", "uploadedAt": "nonsense", "fileName": "b.log"}
                  ]
                }
                """, StandardCharsets.UTF_8);

        UploadHistory history = new UploadHistory(file());

        assertEquals(2, history.size());
        assertEquals("good1", history.last().orElseThrow().id());
        // An unreadable timestamp sorts last but keeps its token.
        assertEquals("t", history.findById("good2").orElseThrow().token());
    }

    @Test
    @DisplayName("the file holds credentials, so nobody but its owner may read it")
    void keepsTheFilePrivate() throws IOException {
        UploadHistory history = new UploadHistory(file());
        history.add("AbCd2345", "url", "token", "latest.log");

        // Both branches are the same assertion in two vocabularies: the file
        // names its owner and nobody else. Skipping the Windows half is what
        // let an inherited ACL naming five other principals go unnoticed.
        if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file())));
            return;
        }

        AclFileAttributeView view = Files.getFileAttributeView(file(), AclFileAttributeView.class);
        assertNotNull(view, "no filesystem view can express who may read this file");
        List<UserPrincipal> allowed = view.getAcl().stream().map(AclEntry::principal).distinct().toList();
        assertEquals(List.of(Files.getOwner(file())), allowed);
    }

    @Test
    void refusesRecordsWithoutAnId() {
        assertThrows(IllegalArgumentException.class,
                () -> new UploadRecord("  ", "url", "token", Instant.now(), "latest.log"));
        assertThrows(NullPointerException.class,
                () -> new UploadRecord(null, "url", "token", Instant.now(), "latest.log"));
    }

    @Test
    @DisplayName("a server that answers without a delete token is still worth remembering for its link")
    void toleratesAMissingToken() {
        UploadHistory history = new UploadHistory(file());

        UploadRecord record = history.add("AbCd2345", "https://alacraft.day/en/logs/AbCd2345", null, null);

        assertFalse(record.hasToken());
        assertEquals("", record.fileName());
        assertEquals("", new UploadHistory(file()).findById("AbCd2345").orElseThrow().token());
    }
}
