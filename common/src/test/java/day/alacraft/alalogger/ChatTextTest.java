package day.alacraft.alalogger;

import day.alacraft.alalogger.api.Insight;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatTextTest {

    private static final char ESCAPE = '\u00a7';

    @Test
    @DisplayName("a formatting escape cannot survive into a chat line")
    void neutralisesTheFormattingEscape() {
        assertEquals("&cnot really an error", ChatText.plain(ESCAPE + "cnot really an error"));
        assertEquals("&k&l&r", ChatText.plain("" + ESCAPE + 'k' + ESCAPE + 'l' + ESCAPE + 'r'));
    }

    @Test
    @DisplayName("a newline would let one message pretend to be two")
    void flattensControlCharacters() {
        assertEquals("first second", ChatText.plain("first\nsecond"));
        assertEquals("no carriage return", ChatText.plain("no\rcarriage return"));
        assertEquals("tab here", ChatText.plain("tab\there"));
        assertEquals("delete here", ChatText.plain("delete\u007fhere"));
    }

    @Test
    void leavesOrdinaryTextExactlyAsItWas() {
        String message = "Java 17 is installed, but this version needs Java 25.";

        // Same instance, not merely an equal one: nothing is copied unless
        // something has to change, and every message the real site sends takes
        // this path.
        assertSame(message, ChatText.plain(message));

        // Including the languages the mod ships in, where most characters are
        // above the ASCII range and none of them is a formatting escape.
        String german = "Nicht genügend Speicher";
        assertSame(german, ChatText.plain(german));
    }

    @Test
    @DisplayName("a finding too long to encode is cut, not thrown")
    void boundsTheLength() {
        // A chat component travels as NBT, whose string tag length is two
        // unsigned bytes. A response is capped at a megabyte, so a hostile or
        // broken instance can return far more than a component can hold, and the
        // failure would be an exception on the thread answering the command.
        String enormous = "x".repeat(50_000);
        String bounded = ChatText.plain(enormous);

        assertTrue(bounded.length() <= 512, "actual: " + bounded.length());
        assertTrue(bounded.endsWith("\u2026"), "the cut is visible: " + bounded.substring(bounded.length() - 3));

        String exact = "y".repeat(512);
        assertSame(exact, ChatText.plain(exact), "text that already fits is untouched");
    }

    @Test
    @DisplayName("a cut never lands inside a surrogate pair")
    void doesNotSplitAnEmoji() {
        // 511 filler characters put the pair exactly astride the cut.
        String withEmoji = "z".repeat(511) + "\ud83d\udca5" + "z".repeat(100);
        String bounded = ChatText.plain(withEmoji);

        for (int i = 0; i < bounded.length(); i++) {
            char c = bounded.charAt(i);
            boolean lone = Character.isHighSurrogate(c)
                    ? i + 1 >= bounded.length() || !Character.isLowSurrogate(bounded.charAt(i + 1))
                    : Character.isLowSurrogate(c) && (i == 0 || !Character.isHighSurrogate(bounded.charAt(i - 1)));

            assertFalse(lone, "a lone surrogate at " + i);
        }
    }

    @Test
    @DisplayName("invisible characters that reorder or hide the rest of the line")
    void neutralisesTheInvisibleOnes() {
        // U+202E reverses everything after it, so a link can be made to read as
        // something it is not. U+0085 is a line break to several log readers, and
        // these sentences reach the log file as well as the chat. The zero-width
        // marks make two different strings look like one.
        assertEquals("a b", ChatText.plain("a\u202eb"), "right-to-left override");
        assertEquals("a b", ChatText.plain("a\u2066b"), "directional isolate");
        assertEquals("a b", ChatText.plain("a\u0085b"), "next line, from the C1 block");
        assertEquals("a b", ChatText.plain("a\u200bb"), "zero-width space");
        assertEquals("a b", ChatText.plain("a\ufeffb"), "byte-order mark");

        // And the spaces that are legitimately spaces stay.
        String narrow = "a\u202fb";
        assertSame(narrow, ChatText.plain(narrow), "a narrow no-break space is a real character");
    }

    @Test
    void treatsNothingAsEmpty() {
        assertEquals("", ChatText.plain(null));
        assertEquals("", ChatText.plain(""));
    }

    @Test
    @DisplayName("a finding is cleaned as it is parsed, not where it is printed")
    void findingsAreCleanedOnTheWayIn() {
        // The seam that matters. A self-hosted instance is a legitimate setup and
        // an unfriendly one is therefore possible, so the text is neutralised
        // where it enters the mod - once, for every platform that will ever print
        // it - rather than at each place that prints it.
        Insight insight = new Insight(
                "out_of_memory",
                "error",
                ESCAPE + "cServer says: run /op attacker",
                ESCAPE + "kobfuscated",
                OptionalInt.of(12),
                1,
                List.of(new Insight.Solution(ESCAPE + "nunderlined advice")));

        assertFalse(insight.message().indexOf(ESCAPE) >= 0, insight.message());
        assertFalse(insight.hint().indexOf(ESCAPE) >= 0, insight.hint());
        assertFalse(insight.solutions().get(0).text().indexOf(ESCAPE) >= 0);
        assertEquals("&cServer says: run /op attacker", insight.message());
    }
}
