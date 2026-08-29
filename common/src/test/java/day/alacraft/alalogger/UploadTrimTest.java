package day.alacraft.alalogger;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which end of a log survives when it has to be cut.
 *
 * <p>This is the mod's stated advantage over the tool it competes with, so it
 * is worth a test rather than an assumption: a running server's log hides its
 * error at the bottom, and a crash report states its cause at the top.
 */
class UploadTrimTest {

    private String log(int lines) {
        StringBuilder out = new StringBuilder();
        for (int i = 1; i <= lines; i++) {
            out.append("line ").append(i).append('\n');
        }
        return out.toString();
    }

    private int bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8).length;
    }

    @Test
    void keeping_the_tail_drops_the_beginning() {
        String trimmed = UploadService.keepTail(log(1000), 200);

        assertTrue(bytes(trimmed) <= 200, "must fit the budget, was " + bytes(trimmed));
        assertTrue(trimmed.endsWith("line 1000"), "the newest line must survive: " + trimmed);
        assertFalse(trimmed.contains("line 1\n"), "the oldest lines should be gone");
    }

    @Test
    void keeping_the_head_drops_the_end() {
        String trimmed = UploadService.keepHead(log(1000), 200);

        assertTrue(bytes(trimmed) <= 200, "must fit the budget, was " + bytes(trimmed));
        assertTrue(trimmed.startsWith("line 1"), "the first line must survive: " + trimmed);
        assertFalse(trimmed.contains("line 1000"), "the tail should be gone");
    }

    /** Lines are kept whole: a log ending mid-token is worse than one line shorter. */
    @Test
    void lines_are_never_cut_in_half() {
        for (String trimmed : new String[]{
                UploadService.keepTail(log(500), 137),
                UploadService.keepHead(log(500), 137)}) {

            for (String line : trimmed.split("\n")) {
                assertTrue(line.isEmpty() || line.matches("line [0-9]+"),
                        "a partial line survived: '" + line + "'");
            }
        }
    }

    /** A budget smaller than any single line yields nothing rather than a fragment. */
    @Test
    void an_impossible_budget_yields_nothing() {
        assertTrue(UploadService.keepTail(log(10), 2).isEmpty());
        assertTrue(UploadService.keepHead(log(10), 2).isEmpty());
    }

    @Test
    void text_within_budget_is_untouched() {
        String text = log(5).strip();

        assertTrue(UploadService.keepTail(text, 10_000).equals(text));
        assertTrue(UploadService.keepHead(text, 10_000).equals(text));
    }
}
