package day.alacraft.alalogger.redact.rules;

import day.alacraft.alalogger.redact.RuleOutcome;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The false-negative half of these tests protects players; the false-positive
 * half protects the log. Both matter — a rule that masks the mod list is a rule
 * nobody will keep switched on.
 */
class IpAddressRuleTest {

    private final IpAddressRule rule = new IpAddressRule();

    private String redact(String content) {
        return rule.apply(content).content();
    }

    @Test
    void masksAJoiningPlayersAddress() {
        String log = "[12:00:03] [Server thread/INFO]: Alex[/203.0.113.7:51234] logged in";

        assertEquals(
                "[12:00:03] [Server thread/INFO]: Alex[/***.***.***.***:51234] logged in",
                redact(log));
    }

    @Test
    void masksIpv6() {
        String log = "Player[/2001:0db8:85a3:0000:0000:8a2e:0370:7334]:25565 logged in";

        assertTrue(redact(log).contains("[ipv6-removed]"), redact(log));
        assertTrue(redact(log).contains("logged in"));
    }

    @Test
    void countsOnlyWhatItActuallyMasked() {
        RuleOutcome outcome = rule.apply("from 203.0.113.7 and 198.51.100.9 and 127.0.0.1");

        // Two real addresses; loopback is exempt and must not inflate the count,
        // or the chat notice claims to have removed something it left in place.
        assertEquals(2, outcome.count());
        assertTrue(outcome.content().contains("127.0.0.1"));
    }

    @Test
    void keepsLoopbackAndPublicResolvers() {
        String log = "bound to 0.0.0.0, admin on 127.0.0.1, resolver 8.8.8.8 / 1.1.1.1";

        assertEquals(log, redact(log));
    }

    @Test
    void keepsGraphicsDriverVersion() {
        // A group above 255 cannot be an address. This is the line a GPU crash is
        // diagnosed from, so losing it would defeat the purpose of sharing.
        String log = "Graphics card #0 versionInfo: DriverVersion=31.0.15.3623";

        assertEquals(log, redact(log));
    }

    @Test
    @DisplayName("a NeoForge startup mod list keeps its loader version")
    void keepsNeoForgeModListVersion() {
        // Found by publishing a real log from a NeoForge client: the header block
        // came back with the loader version rewritten as an address. Four parts,
        // all below 256, no suffix - and neither of the other two mod-list guards
        // matches this shape, because NeoForge prints "Name Version (modid)"
        // rather than Fabric's "modid: Name Version".
        //
        // The line that mattered most: it is where anyone reading a shared log
        // finds out which loader and which build produced it.
        String log = String.join("\n",
                "\t\tAla Logger 0.1.0 (alalogger)",
                "\t\tMinecraft 26.2 (minecraft)",
                "\t\tNeoForge 26.2.0.67 (neoforge)");

        assertEquals(log, redact(log));
    }

    @Test
    @DisplayName("but an address still gets masked on a line that merely ends in brackets")
    void masksAnAddressOnALineThatOnlyLooksLikeAModRow() {
        // The guard above is bounded on purpose. A log line ending in a
        // parenthetical is common; one that ends in a lowercase mod id preceded by
        // a version is not. This is the line that proves the bound holds.
        String log = "  Player123 connected from 203.0.113.7 (first join)";

        assertEquals("  Player123 connected from ***.***.***.*** (first join)", redact(log));
    }

    @Test
    void keepsFabricModListVersion() {
        // Four parts, all below 256 — indistinguishable from an address by shape
        // alone, and the most common way a mod list comes back mangled.
        String log = String.join("\n",
                "\tFabric Mods:",
                "\t\tjei: Just Enough Items 15.2.0.27",
                "\t\tfabricloader: Fabric Loader 0.19.3");

        assertEquals(log, redact(log));
    }

    @Test
    void keepsNeoForgeModTableVersion() {
        // The format a real Minecraft 26.x crash report uses. Taken verbatim in
        // shape from crash-2026-04-25_15.10.58-server.txt.
        String log = "\t\tjei-15.2.0.27.jar   |Just Enough Items   |jei   |15.2.0.27   |Manifest: NOSIGNATURE";

        assertEquals(log, redact(log));
    }

    @Test
    void keepsTheNeoForgeVersionInStackFrames() {
        // Dozens of frames per report look like this. Not a mod-list line, so no
        // line-level guard can save it — the version suffix is what tells them
        // apart from an address.
        String log = "\tat TRANSFORMER/neoforge@26.1.2.28-beta/net.neoforged.neoforge.event.EventHooks.onEntityTick(EventHooks.java:120)";

        assertEquals(log, redact(log));
    }

    @Test
    void keepsLibraryVersionsFromTheLoadedDllInventory() {
        // Verbatim rows from crash-2026-04-25_06.00.44-client.txt, where thirteen
        // of these came back masked before the guard covered the shape. This
        // section is how an injected overlay or anti-cheat DLL is identified as
        // the cause of a crash, and the version is usually the deciding detail.
        String log = String.join("\n",
                "\t\tjvm.dll:OpenJDK 64-Bit server VM:25.0.1.0:Microsoft",
                "\t\tjava.dll:OpenJDK Platform binary:25.0.1.0:Microsoft",
                "\t\tglfw.dll:GLFW 3.5.0 DLL:3.5.0:GLFW",
                "\t\tjemalloc.dll");

        assertEquals(log, redact(log));
    }

    @Test
    void keepsAVersionThatIsLabelledAsOne() {
        String log = "Loading version: 1.16.5.1 of the pack";

        assertEquals(log, redact(log));
    }

    @Test
    void keepsLogTimestamps() {
        // "12:34:56" is two colon-groups; the IPv6 pattern needs at least three
        // plus a tail, which is the only thing keeping it off every single line.
        String log = "[12:34:56] [Server thread/INFO]: Done (5.309s)!";

        assertEquals(log, redact(log));
    }
}
