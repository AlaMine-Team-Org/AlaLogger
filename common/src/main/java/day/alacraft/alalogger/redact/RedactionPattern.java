package day.alacraft.alalogger.redact;

import java.util.List;
import java.util.regex.Pattern;

/**
 * One regex plus the fixed text it is replaced with, and an optional literal
 * "hint" used to skip the regex entirely.
 *
 * <p><b>Why the hint exists.</b> On the site these rules run on a web request
 * against a log someone chose to paste. Here they run inside the game, on the
 * main thread of a JVM that has just crashed or is about to upload while a
 * server ticks, over a file that can be the full 10 MiB the API accepts. About
 * thirty patterns over ten megabytes is 300 MB of scanning, and Java's
 * {@link Pattern} only gets its Boyer-Moore fast path when a pattern
 * <em>starts</em> with a literal — {@code \bUSERNAME=} and
 * {@code (?<!\w)/home/…} do not, so they would be walked one character at a
 * time.
 *
 * <p>Nearly every pattern here needs some literal to be present before it can
 * possibly match ({@code --accessToken}, {@code World Seed:}, {@code /home/}).
 * {@link String#indexOf(int)} is a JIT intrinsic and runs at memory speed, so
 * checking the literal first and skipping the regex when it is absent turns the
 * usual case — a server log with no crash, no token and no home directory in it
 * — into a handful of fast scans.
 *
 * <p>A hint is a correctness claim: <em>this pattern cannot match unless this
 * literal is present</em>. Getting one wrong silently disables a rule, so hints
 * are only ever taken from a mandatory literal inside the pattern, and they are
 * matched case-insensitively even when the pattern is case-sensitive — that
 * direction can only let more text through to the regex, never less.
 *
 * @param pattern     the compiled expression
 * @param replacement literal replacement text; never a {@code $1}-style template,
 *                    because keeping the flag name visible is done by writing it
 *                    into the replacement, and backreference syntax would then
 *                    have to be escaped in every Windows path
 * @param hints       literals of which at least one must be present for
 *                    {@code pattern} to have any chance; empty means "always run"
 */
public record RedactionPattern(Pattern pattern, String replacement, List<String> hints) {

    public RedactionPattern {
        hints = List.copyOf(hints);
    }

    public static RedactionPattern of(String regex, String replacement, String... hints) {
        return new RedactionPattern(Pattern.compile(regex), replacement, List.of(hints));
    }

    public static RedactionPattern ofIgnoreCase(String regex, String replacement, String... hints) {
        return new RedactionPattern(
                Pattern.compile(regex, Pattern.CASE_INSENSITIVE), replacement, List.of(hints));
    }

    /** False only when no hint is present, in which case the regex cannot match. */
    public boolean mightMatch(String content) {
        if (hints.isEmpty()) {
            return true;
        }
        for (String hint : hints) {
            if (containsIgnoreCase(content, hint)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Case-insensitive {@code contains}, without allocating a lower-cased copy of
     * a ten-megabyte log.
     *
     * <p>Candidate positions are found with {@link String#indexOf(int, int)} on
     * the first character in both cases — that call is intrinsified into a
     * vectorised scan — and only those positions pay for a full comparison.
     *
     * <p>The two cases are tracked as separate cursors, and a cursor that has run
     * out is never searched again. That is not a micro-optimisation: the obvious
     * version, which searches for both cases on every iteration, is quadratic
     * whenever one case is common and the other is absent — and that is the
     * ordinary situation. Hunting for "eyJ" in a hex dump finds an 'e' two or
     * three times per line and then rescans the entire remaining log for a capital
     * 'E' that is not there, once per hit. Measured at 6 seconds on a 2.7 MiB
     * dump, against 30 ms for this version.
     */
    static boolean containsIgnoreCase(String haystack, String needle) {
        int length = needle.length();
        if (length == 0) {
            return true;
        }

        char lower = Character.toLowerCase(needle.charAt(0));
        char upper = Character.toUpperCase(needle.charAt(0));
        int limit = haystack.length() - length;

        int nextLower = haystack.indexOf(lower);
        int nextUpper = lower == upper ? -1 : haystack.indexOf(upper);

        while (true) {
            int at;
            if (nextLower < 0) {
                at = nextUpper;
            } else if (nextUpper < 0) {
                at = nextLower;
            } else {
                at = Math.min(nextLower, nextUpper);
            }

            if (at < 0 || at > limit) {
                return false;
            }
            if (haystack.regionMatches(true, at, needle, 0, length)) {
                return true;
            }

            if (nextLower == at) {
                nextLower = haystack.indexOf(lower, at + 1);
            }
            if (nextUpper == at) {
                nextUpper = haystack.indexOf(upper, at + 1);
            }
        }
    }
}
