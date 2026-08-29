package day.alacraft.alalogger.redact.rules;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class UserPathRuleTest {

    private final UserPathRule rule = new UserPathRule();

    private String redact(String content) {
        return rule.apply(content).content();
    }

    @Test
    void masksWindowsPaths() {
        assertEquals(
                "Failed to load C:\\Users\\********\\AppData\\Roaming\\.minecraft\\mods\\foo.jar",
                redact("Failed to load C:\\Users\\Ivan\\AppData\\Roaming\\.minecraft\\mods\\foo.jar"));
    }

    @Test
    void masksJsonEscapedWindowsPaths() {
        assertEquals(
                "{\"gameDir\": \"C:\\\\Users\\\\********\\\\AppData\"}",
                redact("{\"gameDir\": \"C:\\\\Users\\\\Ivan\\\\AppData\"}"));
    }

    @Test
    void masksWindowsPathsWrittenWithForwardSlashes() {
        assertEquals(
                "gameDir=C:/Users/********/AppData/Roaming",
                redact("gameDir=C:/Users/Ivan/AppData/Roaming"));
    }

    @Test
    void countsAForwardSlashWindowsPathOnce() {
        // The macOS pattern legitimately matches the already-masked result and
        // rewrites it to the identical string; that must not be counted twice.
        assertEquals(1, rule.apply("gameDir=C:/Users/Ivan/AppData").count());
    }

    @Test
    void masksLinuxAndMacHomeDirectories() {
        assertEquals(
                "reading /home/********/server/mods and /Users/********/Library/caches",
                redact("reading /home/artem/server/mods and /Users/artem/Library/caches"));
    }

    @Test
    void masksAHomeDirectoryWithNoTrailingSlash() {
        // The structural hole in the upstream patterns: every one of them needed a
        // separator after the name, and the Environment Variables block of an
        // hs_err file is made almost entirely of values that end at the name.
        assertEquals("HOME=/home/********", redact("HOME=/home/artem"));
    }

    @Test
    void masksTheEnvironmentVariablesBlock() {
        String log = String.join("\n",
                "USERNAME=Steve",
                "USER=artem",
                "LOGNAME=artem",
                "HOSTNAME=gaming-pc",
                "COMPUTERNAME=DESKTOP-4F2K1",
                "USERDOMAIN=WORKGROUP",
                "LOGONSERVER=\\\\DESKTOP-4F2K1",
                "USERPROFILE=C:\\Users\\John Smith");

        assertEquals(String.join("\n",
                "USERNAME=********",
                "USER=********",
                "LOGNAME=********",
                "HOSTNAME=********",
                "COMPUTERNAME=********",
                "USERDOMAIN=********",
                "LOGONSERVER=********",
                "USERPROFILE=********"), redact(log));
    }

    @Test
    void masksSystemPropertyDumps() {
        assertEquals(
                "user.name=********\nuser.home=********",
                redact("user.name = Steve\nuser.home = C:\\Users\\User"));
    }

    @Test
    void keepsUrlsThatHappenToContainHome() {
        // The lookbehind exists for this: "…example.com/home/" is a web path, not
        // somebody's account.
        String log = "Downloading from https://example.com/home/pack.zip";

        assertEquals(log, redact(log));
    }

    @Test
    void keepsAnUnrelatedVariableThatEndsInUser() {
        // \bUSER= must not fire inside a longer name; there is no word boundary in
        // the middle of a word.
        String log = "SUPERUSER=root";

        assertEquals(log, redact(log));
    }

    @Test
    void keepsThePlayersMinecraftName() {
        // Deliberate policy: the nickname is public anyway, and a log with it
        // blanked out is much harder to read.
        String log = "ServerPlayer['Ma3auka'/230, l='ServerLevel[world]', x=-50.98]";

        assertEquals(log, redact(log));
    }
}
