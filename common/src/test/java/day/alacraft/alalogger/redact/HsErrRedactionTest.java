package day.alacraft.alalogger.redact;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The whole point of the module, on the file it exists for.
 *
 * <p>A JVM fatal-error file is the worst artefact a Minecraft player can be asked
 * to share: it carries the session token twice, the OS account name in a hundred
 * paths, the machine name, the world seed and several kilobytes of raw process
 * memory. Tools that decline to handle these files, or upload them unedited, are
 * why sharing one is normally a bad idea. Being the place where an hs_err file can
 * safely be shared is the product.
 *
 * <p>So this test asserts both halves of that promise: everything dangerous is
 * gone, <em>and</em> the file is still worth reading afterwards — the player's
 * name, the problematic frame and the driver that caused the crash all survive.
 * A redactor that passes only the first half has turned a crash report into
 * confetti.
 *
 * <p>Shape taken from a real Temurin 25 hs_err file plus the launch arguments the
 * vanilla launcher passes, as documented in {@code docs/research/06-crash-safety.md}.
 */
class HsErrRedactionTest {

    private static final String TOKEN =
            "eyJhbGciOiJIUzI1NiJ9.eyJ4dWlkIjoiMjUzNTQxMjM0NTY3ODkwMSJ9.dBjftJeZ4CVPmB92K27uhbUJU1p1r_wW1gFWFOEjXk";

    private static final String COMMAND_LINE = "net.minecraft.client.main.Main"
            + " --username Alex"
            + " --version 26.2"
            + " --gameDir C:\\Users\\Steve\\AppData\\Roaming\\.minecraft"
            + " --accessToken " + TOKEN
            + " --uuid 069a79f4a1b2c3d4e5f61234567890ab"
            + " --xuid 2535412345678901"
            + " --clientId MTIzNDU2Nzg5MDEyMzQ1Njc4OTA="
            + " --session token:abc123:069a79f4"
            + " --userType msa";

    private static final String HS_ERR = String.join("\n",
            "#",
            "# A fatal error has been detected by the Java Runtime Environment:",
            "#",
            "#  EXCEPTION_ACCESS_VIOLATION (0xc0000005) at pc=0x00007ff8a1b2c3d4, pid=10280, tid=32752",
            "#",
            "# JRE version: OpenJDK Runtime Environment Temurin-25.0.2+10 (25.0.2+10)",
            "# Java VM: OpenJDK 64-Bit Server VM (25.0.2+10, mixed mode, windows-amd64)",
            "# Problematic frame:",
            "# C  [nvoglv64.dll+0x8f4a2b]",
            "#",
            "---------------  S U M M A R Y ------------",
            "",
            "Command Line: -Xmx4G " + COMMAND_LINE,
            "Host: AMD Ryzen 5 3600 6-Core Processor, 12 cores, 31G, Windows 10",
            "",
            "---------------  T H R E A D  ---------------",
            "",
            "Current thread (0x000001f4c0d3e5a0):  JavaThread \"Render thread\" [id=32752]",
            "",
            "Registers:",
            "RAX=0x0000000000000001, RBX=0x000001f4c0d3e5a0",
            "RSP=0x000000f0f36fe8c0, RBP=0x000000f0f36fe9a0",
            "",
            "Register to memory mapping:",
            "",
            "RBX=0x000001f4c0d3e5a0 points into object: <Alex> my base is at 1200 64 -3400",
            "",
            "Top of Stack: (sp=0x000000f0f36fe8c0)",
            "0x000000f0f36fe8c0:   00007ff8a1b2c3d4 000001f4c0d3e5a0",
            "0x000000f0f36fe8d0:   6f6c6c6568 " + TOKEN,
            "",
            "Instructions: (pc=0x00007ff8a1b2c3d4)",
            "0x00007ff8a1b2c3b4:   48 89 e5 41 57 41 56 41 55 41 54 53 48 83 ec 28",
            "",
            "---------------  P R O C E S S  ---------------",
            "",
            "Dynamic libraries:",
            "0x00007ff8a1000000 C:\\Program Files\\Java\\bin\\java.exe",
            "0x00007ff8a2000000 C:\\Users\\Steve\\AppData\\Local\\Temp\\natives\\lwjgl.dll",
            "0x00007ff8a3000000 C:\\Windows\\System32\\nvoglv64.dll",
            "",
            "VM Arguments:",
            "jvm_args: -Xmx4G -Dhttp.proxyPassword=hunter2secret",
            "java_command: " + COMMAND_LINE,
            "java_class_path (initial): C:\\Users\\Steve\\AppData\\Roaming\\.minecraft\\libraries\\lwjgl.jar",
            "Launcher Type: SUN_STANDARD",
            "",
            "Environment Variables:",
            "JAVA_HOME=C:\\Program Files\\Eclipse Adoptium\\jdk-25.0.2.10-hotspot",
            "PATH=C:\\Windows\\System32;C:\\Users\\Steve\\AppData\\Local\\Programs\\bin",
            "USERNAME=Steve",
            "USERPROFILE=C:\\Users\\Steve",
            "COMPUTERNAME=DESKTOP-4F2K1",
            // Not standard on Windows, but a JVM launched from git-bash or MSYS
            // inherits it — and on Linux it is always there. It is the value that
            // ends at the account name, with no separator after it.
            "HOME=/home/steve",
            "TMP=C:\\Users\\Steve\\AppData\\Local\\Temp",
            "",
            "---------------  S Y S T E M  ---------------",
            "",
            "OS: Windows 10, Build 19041",
            "",
            "-- System Details --",
            "\tMinecraft Version: 26.2",
            "\tWorld Seed: 7026191857309640518",
            "\tPlayer Count: 1/8; [ServerPlayer['Alex'/230, l='ServerLevel[world]']]",
            "\tMod List:",
            "\t\tneoforge-26.1.2.28-beta-universal.jar |NeoForge |neoforge |26.1.2.28-beta |Manifest: 631aab87",
            "\t\tjei-15.2.0.27.jar                     |Just Enough Items |jei |15.2.0.27 |Manifest: NOSIGNATURE",
            "END.");

    private final RedactionResult result = new Redactor().redact(HS_ERR);
    private final String clean = result.content();

    @Test
    void removesTheAccessTokenFromBothCopies() {
        // Command Line: in SUMMARY and java_command: under VM Arguments. A rule
        // scoped to one section would publish the other.
        assertFalse(clean.contains(TOKEN), clean);
        assertFalse(clean.contains("eyJ"), clean);
        assertEquals(2, occurrences(clean, "--accessToken ****"));
    }

    @Test
    void removesTheOtherAccountIdentifiers() {
        assertFalse(clean.contains("2535412345678901"), clean);
        assertFalse(clean.contains("MTIzNDU2Nzg5MDEyMzQ1Njc4OTA"), clean);
        assertFalse(clean.contains("token:abc123"), clean);

        assertEquals(2, occurrences(clean, "--xuid ****"));
        assertEquals(2, occurrences(clean, "--clientId ****"));
        assertEquals(2, occurrences(clean, "--session ****"));
    }

    @Test
    void removesTheOperatingSystemAccountName() {
        assertFalse(clean.contains("Steve"), clean);
        assertFalse(clean.contains("steve"), clean);

        assertTrue(clean.contains("USERNAME=********"), clean);
        assertTrue(clean.contains("USERPROFILE=********"), clean);
        assertTrue(clean.contains("COMPUTERNAME=********"), clean);
        assertTrue(clean.contains("C:\\Users\\********\\AppData"), clean);
    }

    @Test
    void removesAHomePathThatEndsAtTheAccountName() {
        // HOME=/home/steve has no separator after the name, which every upstream
        // path pattern required. This one line is why the rule was extended.
        assertTrue(clean.contains("HOME=/home/********"), clean);
    }

    @Test
    void removesTheWorldSeed() {
        assertFalse(clean.contains("7026191857309640518"), clean);
        assertTrue(clean.contains("World Seed: ********"), clean);
    }

    @Test
    void removesTheRawMemoryDumps() {
        // Everything a regex cannot clean, because the content is arbitrary: the
        // token that happened to be on the stack, and the coordinates that
        // happened to be in a chat buffer.
        assertFalse(clean.contains("my base is at"), clean);
        assertFalse(clean.contains("00007ff8a1b2c3d4 000001f4c0d3e5a0"), clean);
        assertFalse(clean.contains("48 89 e5"), clean);

        assertEquals(4, result.summary().get("memdump"),
                "Registers, Register to memory mapping, Top of Stack, Instructions");
    }

    @Test
    void removesTheProxyPasswordFromJvmFlags() {
        assertFalse(clean.contains("hunter2secret"), clean);
    }

    @Test
    void keepsTheLogWorthReading() {
        // The other half of the promise. Everything here is what a diagnosis is
        // actually made from.
        assertTrue(clean.contains("nvoglv64.dll"), "the driver that crashed");
        assertTrue(clean.contains("# Problematic frame:"), "the single most useful line");
        assertTrue(clean.contains("EXCEPTION_ACCESS_VIOLATION (0xc0000005)"), "the crash class");
        assertTrue(clean.contains("Temurin-25.0.2+10"), "the Java build");
        assertTrue(clean.contains("Alex"), "the player's name is public and is left alone");
        assertTrue(clean.contains("--username Alex"), "and stays readable in the command line");
        assertTrue(clean.contains("OS: Windows 10, Build 19041"), "the platform");

        // The section headers survive their emptied bodies, so it is obvious what
        // was cut rather than looking like a corrupt file.
        for (String header : List.of("Registers:", "Register to memory mapping:",
                "Top of Stack: (sp=", "Instructions: (pc=")) {
            assertTrue(clean.contains(header), header + " should still be there");
        }

        // The mod list is the fuel of every modded crash diagnosis, and its
        // four-part versions look exactly like addresses.
        assertTrue(clean.contains("|26.1.2.28-beta |"), clean);
        assertTrue(clean.contains("|15.2.0.27 |"), clean);
    }

    @Test
    void reportsWhatItDid() {
        assertTrue(result.matchedSummary().containsKey("token"));
        assertTrue(result.matchedSummary().containsKey("path"));
        assertTrue(result.matchedSummary().containsKey("seed"));
        assertTrue(result.matchedSummary().containsKey("memdump"));
        assertTrue(result.matchedSummary().containsKey("secret"));
        assertFalse(result.isClean());
    }

    private static int occurrences(String haystack, String needle) {
        int count = 0;
        for (int at = haystack.indexOf(needle); at >= 0; at = haystack.indexOf(needle, at + needle.length())) {
            count++;
        }
        return count;
    }
}
