package day.alacraft.alalogger.redact.rules;

import day.alacraft.alalogger.redact.RuleOutcome;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JvmMemoryDumpRuleTest {

    private final JvmMemoryDumpRule rule = new JvmMemoryDumpRule();

    private static String hsErr(String... body) {
        String[] lines = new String[body.length + 2];
        lines[0] = "#";
        lines[1] = "# " + JvmMemoryDumpRule.HS_ERR_MARKER + ":";
        System.arraycopy(body, 0, lines, 2, body.length);
        return String.join("\n", lines);
    }

    @Test
    void removesARegisterDumpButKeepsItsHeader() {
        String log = hsErr(
                "Registers:",
                "RAX=0x0000000000000001, RBX=0x000001f4c0d3e5a0",
                "RCX=0x000001f4c0d3e5a0, RDX=0x0000000000000000",
                "",
                "Top of Stack: (sp=0x000000f0f36fe8c0)",
                "0x000000f0f36fe8c0:   00007ffc8b3c1234 000001f4c0d3e5a0",
                "",
                "---------------  P R O C E S S  ---------------");

        RuleOutcome outcome = rule.apply(log);
        String clean = outcome.content();

        assertTrue(clean.contains("Registers:"), clean);
        assertTrue(clean.contains("Top of Stack: (sp=0x000000f0f36fe8c0)"), clean);
        assertTrue(clean.contains("---------------  P R O C E S S  ---------------"), clean);

        assertFalse(clean.contains("RAX=0x0000000000000001"), clean);
        assertFalse(clean.contains("00007ffc8b3c1234"), clean);

        assertEquals("[2 lines removed for safety]", clean.lines().toList().get(3));
        assertEquals(2, outcome.count(), "two sections, counted as sections not lines");
    }

    @Test
    void doesNothingWithoutTheJvmMarker() {
        // "Registers:" and "Instructions:" are ordinary words a mod could print.
        // Cutting them out of an ordinary server log would delete real content.
        String log = String.join("\n",
                "[12:00:00] [main/INFO]: Instructions:",
                "[12:00:00] [main/INFO]: 1. place the block",
                "[12:00:00] [main/INFO]: 2. right-click it");

        RuleOutcome outcome = rule.apply(log);

        assertEquals(log, outcome.content());
        assertEquals(0, outcome.count());
    }

    @Test
    void handlesTheBlankLineUnderRegisterToMemoryMapping() {
        // This header is followed by an empty line before its body. Treating that
        // blank line as the end of the section would cut nothing at all — the one
        // section most likely to hold readable text would survive intact.
        String log = hsErr(
                "Register to memory mapping:",
                "",
                "RAX=0x000001f4c0d3e5a0 is pointing into object: chat buffer",
                "RBX=0x0000000000000000 is null",
                "",
                "Instructions: (pc=0x00007ffc8b3c1234)");

        String clean = rule.apply(log).content();

        assertTrue(clean.contains("Register to memory mapping:"));
        assertFalse(clean.contains("chat buffer"), clean);
        assertTrue(clean.contains("[2 lines removed for safety]"), clean);
    }

    @Test
    void removesAMachCodeBlockIncludingItsInternalBlankLines() {
        String log = hsErr(
                "[MachCode]",
                "  0x00007ffc8b3c1234: 48 89 e5 41 57",
                "",
                "  0x00007ffc8b3c1244: 5d c3 90 90 90",
                "[/MachCode]",
                "",
                "Problematic frame:");

        String clean = rule.apply(log).content();

        assertFalse(clean.contains("48 89 e5"), clean);
        assertFalse(clean.contains("[/MachCode]"), clean);
        assertTrue(clean.contains("[MachCode]"), clean);
        assertTrue(clean.contains("Problematic frame:"), clean);
    }

    @Test
    void stopsAnUnterminatedMachCodeAtTheNextSectionBanner() {
        // A file truncated by the crash itself. Without the banner backstop
        // everything after the header would be thrown away.
        String log = hsErr(
                "[MachCode]",
                "  0x00007ffc8b3c1234: 48 89 e5 41 57",
                "---------------  S Y S T E M  ---------------",
                "OS: Windows 10, Build 19041");

        String clean = rule.apply(log).content();

        assertFalse(clean.contains("48 89 e5"), clean);
        assertTrue(clean.contains("OS: Windows 10, Build 19041"), clean);
    }

    @Test
    void isIdempotent() {
        // The log is cleaned here and cleaned again on arrival at alacraft.day; a
        // second pass must not eat the marker the first one wrote and report "1
        // line removed" over a section that had two hundred.
        String log = hsErr(
                "Registers:",
                "RAX=0x0000000000000001",
                "RBX=0x0000000000000002",
                "",
                "VM state: not at safepoint");

        String once = rule.apply(log).content();
        RuleOutcome twice = rule.apply(once);

        assertEquals(once, twice.content());
        assertEquals(0, twice.count());
    }
}
