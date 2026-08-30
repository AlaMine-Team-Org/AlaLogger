package day.alacraft.alalogger;

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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The only file in this mod a human edits by hand.
 *
 * <p>It was also the only class of its kind with no tests at all, which is how a
 * missing {@code https://} came to stop a server from starting: the repair lived
 * one floor up, in the API client's builder, and threw out of the entrypoint. The
 * first three tests here are about that specific hole.
 */
class ConfigTest {

    @TempDir
    Path directory;

    private Path file() {
        return directory.resolve("alalogger.json");
    }

    private Config write(String json) {
        try {
            Files.writeString(file(), json, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new AssertionError(e);
        }

        return Config.load(file());
    }

    @Test
    @DisplayName("an apiBaseUrl the client would refuse is replaced, not passed on")
    void repairsAnUnusableBaseUrl() {
        // Every one of these used to reach AlaLoggerApi.Builder and throw there.
        for (String broken : new String[] {"alacraft.day/api/v1", "ftp://alacraft.day/api", "", "   "}) {
            Config config = write("{\"apiBaseUrl\": \"" + broken + "\"}");

            assertEquals(AlaLogger.DEFAULT_API_BASE_URL, config.apiBaseUrl, broken);
        }
    }

    @Test
    @DisplayName("a file with a broken apiBaseUrl is left alone, so the mistake stays visible")
    void doesNotOverwriteABrokenBaseUrl() {
        // The mod runs on the default, but the file keeps what the operator
        // wrote. Rewriting it would replace their address with ours: the typo
        // they have to correct would be gone, and the file would then read as
        // valid, so nothing would ever complain again - a self-hoster left
        // uploading to alacraft.day without being told.
        String written = "{\"apiBaseUrl\": \"logs.example.org/api/v1\"}";
        Config config = write(written);

        assertEquals(AlaLogger.DEFAULT_API_BASE_URL, config.apiBaseUrl, "the mod still works");

        try {
            assertEquals(written, Files.readString(file(), StandardCharsets.UTF_8),
                    "the operator's value must survive on disk");
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    @DisplayName("a blank or missing apiBaseUrl is not a mistake, so that file is rewritten")
    void stillRewritesAFileThatSimplyWantsTheDefault() {
        write("{\"apiBaseUrl\": \"   \"}");

        String rewritten;
        try {
            rewritten = Files.readString(file(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new AssertionError(e);
        }

        assertTrue(rewritten.contains(AlaLogger.DEFAULT_API_BASE_URL), rewritten);
        assertTrue(rewritten.contains("broadcastToAdmins"), "and gains the keys it was missing");
    }

    @Test
    void keepsAUsableBaseUrlAndTidiesIt() {
        assertEquals("https://logs.example.org/api/v1",
                write("{\"apiBaseUrl\": \"  https://logs.example.org/api/v1//  \"}").apiBaseUrl);
        assertEquals("http://127.0.0.1:8123/api/v1",
                write("{\"apiBaseUrl\": \"http://127.0.0.1:8123/api/v1\"}").apiBaseUrl,
                "a local instance is a legitimate setup");
    }

    @Test
    @DisplayName("a damaged file never stops a server, and is left on disk to be looked at")
    void survivesRubbish() {
        String rubbish = "{ this is not json";
        Config config = write(rubbish);

        assertEquals(AlaLogger.DEFAULT_API_BASE_URL, config.apiBaseUrl);
        assertEquals(3, config.insightsInChat);
        assertTrue(config.crashWatch);

        try {
            assertEquals(rubbish, Files.readString(file(), StandardCharsets.UTF_8),
                    "the unreadable file is the only copy of what the player wrote");
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void ignoresValuesOfTheWrongShape() {
        Config config = write("""
                {
                  "insightsInChat": "three",
                  "apiToken": 42
                }
                """);

        assertEquals(3, config.insightsInChat, "a word where a number belongs falls back");
        // A number where a string belongs is still readable as one; what matters
        // is that nothing here throws.
        assertEquals("42", config.apiToken);
    }

    @Test
    @DisplayName("a word where a boolean belongs reads as false, which is worth knowing")
    void aNonBooleanWordTurnsAFlagOff() {
        // Not the behaviour you would design. Gson reads any string primitive as
        // a boolean by parsing it, and everything that is not "true" parses as
        // false - so `"crashWatch": "yes"` silently turns the crash watch off
        // rather than falling back to the default, and nothing says so.
        //
        // Recorded rather than changed: the fix belongs with the other config
        // hardening, not smuggled into a refactor. This test is what will notice
        // when it is fixed.
        assertFalse(write("{\"crashWatch\": \"yes please\"}").crashWatch);
        assertTrue(write("{\"crashWatch\": \"true\"}").crashWatch);
        assertTrue(write("{}").crashWatch, "an absent key does fall back correctly");
    }

    @Test
    void clampsCountsToWhatChatCanShow() {
        assertEquals(0, write("{\"insightsInChat\": -5}").insightsInChat);
        assertEquals(10, write("{\"insightsInChat\": 900}").insightsInChat);
        assertEquals(0, write("{\"insightsInChat\": 0}").insightsInChat, "zero turns them off");
    }

    @Test
    @DisplayName("auto means ask the player; anything else is an answer for everyone")
    void reportsWhichLanguageToUse() {
        Config auto = write("{\"language\": \"auto\"}");
        assertTrue(auto.pinnedLanguage().isEmpty());
        assertEquals("en_us", auto.consoleLanguage(), "a console has nobody to ask");

        Config pinned = write("{\"language\": \"RU_ru\"}");
        assertEquals("ru_ru", pinned.pinnedLanguage().orElseThrow(), "normalised to lower case");
        assertEquals("ru_ru", pinned.consoleLanguage());

        assertTrue(write("{\"language\": \"  \"}").pinnedLanguage().isEmpty(), "blank means auto");
    }

    @Test
    void writesTheFileBackWithEveryKey() {
        write("{}");

        String written;
        try {
            written = Files.readString(file(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new AssertionError(e);
        }

        for (String key : new String[] {"schemaVersion", "apiBaseUrl", "apiToken", "language",
                "insightsInChat", "crashWatch", "persistHistory", "broadcastToAdmins"}) {
            assertTrue(written.contains('"' + key + '"'), key + " is missing from the rewritten file");
        }
    }

    @Test
    void reportsWhetherThereIsATokenToUse() {
        assertFalse(write("{}").hasApiToken());
        assertFalse(write("{\"apiToken\": \"   \"}").hasApiToken());
        assertTrue(write("{\"apiToken\": \"  7|abcdef  \"}").hasApiToken());
        assertEquals("7|abcdef", write("{\"apiToken\": \"  7|abcdef  \"}").apiToken);
    }

    @Test
    @DisplayName("the file holds an API token, so nobody but its owner may read it")
    void keepsTheFilePrivate() throws IOException {
        // The same assertion as the upload history's, in the same two
        // vocabularies. This file was the one that missed out: it was written in
        // place and tightened afterwards, so for an instant a token sat in a
        // world-readable file.
        write("{\"apiToken\": \"7|secret\"}");

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
    void createsTheFileAndItsDirectoryOnFirstRun() {
        Path nested = directory.resolve("config").resolve("alalogger.json");

        Config config = Config.load(nested);

        assertTrue(Files.isRegularFile(nested), "the config is written on first run");
        assertEquals(AlaLogger.DEFAULT_API_BASE_URL, config.apiBaseUrl);
        assertFalse(Files.exists(nested.resolveSibling(nested.getFileName() + ".tmp")),
                "the temporary file used to write it is not left behind");
    }
}
