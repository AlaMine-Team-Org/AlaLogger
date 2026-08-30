package day.alacraft.alalogger.redact;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedactorTest {

    private final Redactor redactor = new Redactor();

    @Test
    void reportsEveryRuleAndOnlyCountsWhatMatched() {
        RedactionResult result = redactor.redact(
                "[12:00:00] [Server thread/INFO]: Alex[/203.0.113.7:51234] logged in");

        assertEquals(
                List.of("memdump", "ip", "email", "path", "seed", "token", "secret"),
                List.copyOf(result.summary().keySet()),
                "keys and their order are part of the protocol shared with the site");

        assertEquals(Map.of("ip", 1), result.matchedSummary());
        assertEquals(1, result.totalRedactions());
        assertFalse(result.isClean());
    }

    @Test
    void saysNothingWasFoundWhenNothingWasFound() {
        RedactionResult result = redactor.redact("[12:00:00] [Server thread/INFO]: Done (5.309s)!");

        assertTrue(result.isClean());
        assertTrue(result.matchedSummary().isEmpty());
    }

    @Test
    void normalisesWindowsLineEndings() {
        // The mod reads files written by a Windows client and by a Linux server;
        // everything downstream counts lines, so they have to agree on what one is.
        RedactionResult result = redactor.redact("first\r\nsecond\rthird\n");

        assertEquals("first\nsecond\nthird", result.content());
        assertEquals(3, result.lines());
    }

    @Test
    void handlesEmptyInput() {
        RedactionResult result = redactor.redact("   \n  \n ");

        assertEquals("", result.content());
        assertEquals(0, result.lines());
        assertTrue(result.isClean());
    }

    @Test
    void truncatesByLineCountAndSaysSo() {
        StringBuilder log = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            log.append("line ").append(i).append('\n');
        }

        Redactor small = new Redactor(Redactor.defaultRules(), Redactor.DEFAULT_MAX_BYTES, 10);
        RedactionResult result = small.redact(log.toString());

        assertTrue(result.truncatedLines());
        assertFalse(result.truncatedBytes());
        assertEquals(10, result.lines());
        assertTrue(result.content().endsWith("line 9"), result.content());
    }

    @Test
    void truncatesByByteBudgetOnAWholeLine() {
        // The cut lands on a line boundary on purpose: a log that ends mid-token
        // still ends on half an access token.
        String log = "aaaaaaaaaa\nbbbbbbbbbb\ncccccccccc\ndddddddddd";

        Redactor small = new Redactor(Redactor.defaultRules(), 25, Redactor.DEFAULT_MAX_LINES);
        RedactionResult result = small.redact(log);

        assertTrue(result.truncatedBytes());
        assertEquals("aaaaaaaaaa\nbbbbbbbbbb", result.content());
    }

    @Test
    void countsTheByteBudgetInBytesNotCharacters() {
        // A Russian or Japanese server log is two to three bytes per character.
        // Cutting on characters would send three times the limit and be rejected
        // on arrival.
        String cyrillic = "привет".repeat(10) + "\n" + "привет".repeat(10);

        Redactor small = new Redactor(Redactor.defaultRules(), 130, Redactor.DEFAULT_MAX_LINES);
        RedactionResult result = small.redact(cyrillic);

        assertTrue(result.truncatedBytes());
        assertTrue(result.content().getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 130);
    }

    @Test
    void deletesAMemoryDumpBeforeAnyPatternCanTripOverIt() {
        // Rule order: the dump goes first, so nothing below it ever scans a page
        // of hex — and a token that happened to be sitting in those bytes is
        // removed rather than masked, because no pattern could recognise it.
        String log = String.join("\n",
                "# " + day.alacraft.alalogger.redact.rules.JvmMemoryDumpRule.HS_ERR_MARKER + ":",
                "Instructions: (pc=0x00007ffc8b3c1234)",
                "0x00007ffc8b3c1224:   68 65 6c 6c 6f  eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NSJ9.QQQQQQQQ",
                "",
                "VM state: not at safepoint");

        RedactionResult result = redactor.redact(log);

        assertFalse(result.content().contains("eyJ"), result.content());
        assertEquals(1, result.summary().get("memdump"));
        assertEquals(0, result.summary().get("token"), "the token went with the dump, not through a pattern");
    }
}
