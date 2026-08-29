package day.alacraft.alalogger.redact;

import day.alacraft.alalogger.redact.rules.AuthTokenRule;
import day.alacraft.alalogger.redact.rules.EmailRule;
import day.alacraft.alalogger.redact.rules.IpAddressRule;
import day.alacraft.alalogger.redact.rules.JvmMemoryDumpRule;
import day.alacraft.alalogger.redact.rules.SecretKeyRule;
import day.alacraft.alalogger.redact.rules.SeedRule;
import day.alacraft.alalogger.redact.rules.UserPathRule;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Strips personal data and secrets out of a log <em>before the mod sends it</em>.
 *
 * <p>Port of {@code app/Services/LogRedaction/Redactor.php} on alacraft.day. The
 * two implementations are kept deliberately identical, rule for rule and pattern
 * for pattern, because the site runs the same pass again on arrival: if they ever
 * disagree, the mod is the one that matters — its output is what crossed the
 * network — and the divergence would only ever be discovered by someone reading
 * both files.
 *
 * <p>The order of the steps is not arbitrary:
 *
 * <ol>
 *   <li>normalise line endings and trim — a Windows client and a Linux server
 *       both end up here, and everything downstream counts lines;
 *   <li>enforce the size limits, so a 400 MiB log never reaches a regex;
 *   <li>run the rules.
 * </ol>
 *
 * <p>Truncating <em>before</em> redacting is on purpose: a regex pass over an
 * oversized string is the one place this class can realistically stall a game
 * thread. Oversized input is truncated rather than rejected — the player with the
 * enormous log is usually the one with the worst problem.
 *
 * <p>Rule order matters too, and is documented at {@link #defaultRules()}.
 *
 * <p>This class is stateless and its rules are immutable, so a single instance
 * can be shared; it is also cheap enough to construct per upload.
 */
public final class Redactor {

    /**
     * Limits mirrored from {@code config/logchecker.php} on the site. Cutting
     * locally to the same numbers means an oversized log is trimmed to something
     * the API will accept instead of being rejected after the upload.
     */
    public static final int DEFAULT_MAX_BYTES = 10 * 1024 * 1024;

    public static final int DEFAULT_MAX_LINES = 25_000;

    private final List<RedactionRule> rules;
    private final int maxBytes;
    private final int maxLines;

    public Redactor() {
        this(defaultRules(), DEFAULT_MAX_BYTES, DEFAULT_MAX_LINES);
    }

    public Redactor(List<RedactionRule> rules, int maxBytes, int maxLines) {
        this.rules = List.copyOf(rules);
        this.maxBytes = maxBytes;
        this.maxLines = maxLines;
    }

    /**
     * The pipeline, in the order it runs.
     *
     * <p>{@link JvmMemoryDumpRule} is first because it is the only rule that
     * <em>deletes</em> text rather than rewriting it. Everything after it then
     * scans a smaller string, and — more importantly — no pattern can trip over
     * half a hex dump: a page of raw memory is exactly the shape that makes a
     * token-like or base64-like pattern backtrack.
     *
     * <p>The rest is the site's order. It is not load-bearing between the regex
     * rules, with one exception worth knowing: {@link AuthTokenRule} runs before
     * {@link SecretKeyRule} so a JWT is masked as a token rather than being
     * caught by the generic Discord-token shape and reported under the wrong key.
     */
    public static List<RedactionRule> defaultRules() {
        return List.of(
                new JvmMemoryDumpRule(),
                new IpAddressRule(),
                new EmailRule(),
                new UserPathRule(),
                new SeedRule(),
                new AuthTokenRule(),
                new SecretKeyRule());
    }

    public List<RedactionRule> rules() {
        return rules;
    }

    public RedactionResult redact(String input) {
        // Normalise CRLF/CR so line counting here, on the site and in the viewer
        // all agree on what a line is.
        String content = input.replace("\r\n", "\n").replace('\r', '\n').strip();

        int cut = utf8Cut(content, maxBytes);
        boolean truncatedBytes = cut >= 0;
        if (truncatedBytes) {
            content = content.substring(0, cut);

            // Drop the trailing partial line so the sent log never ends
            // mid-token — a half-written access token is still an access token.
            int lastNewline = content.lastIndexOf('\n');
            if (lastNewline >= 0) {
                content = content.substring(0, lastNewline);
            }
        }

        int lineCut = indexOfNewline(content, maxLines);
        boolean truncatedLines = lineCut >= 0;
        if (truncatedLines) {
            content = content.substring(0, lineCut);
        }

        Map<String, Integer> summary = new LinkedHashMap<>();
        for (RedactionRule rule : rules) {
            RuleOutcome outcome = rule.apply(content);
            content = outcome.content();
            summary.put(rule.key(), outcome.count());
        }

        return new RedactionResult(content, summary, truncatedBytes, truncatedLines, countLines(content));
    }

    /**
     * Index of the first character that does not fit inside {@code maxBytes} of
     * UTF-8, or {@code -1} when the whole string fits.
     *
     * <p>Counted rather than encoded: {@code getBytes(UTF_8)} on a 10 MiB log
     * allocates a second copy of it, on a JVM that may be minutes away from the
     * heap exhaustion the player is trying to report.
     *
     * <p>The limit is in bytes, not characters, because that is what the API
     * measures — and a Russian or Japanese server log is two to three bytes per
     * character, so a character-based cut would send a log three times over the
     * limit and have it rejected on arrival.
     */
    static int utf8Cut(String text, int maxBytes) {
        long bytes = 0;
        int length = text.length();

        for (int i = 0; i < length; i++) {
            char c = text.charAt(i);
            int cost;
            boolean surrogatePair = false;

            if (c < 0x80) {
                cost = 1;
            } else if (c < 0x800) {
                cost = 2;
            } else if (Character.isHighSurrogate(c)
                    && i + 1 < length
                    && Character.isLowSurrogate(text.charAt(i + 1))) {
                cost = 4;
                surrogatePair = true;
            } else {
                cost = 3;
            }

            if (bytes + cost > maxBytes) {
                return i;
            }

            bytes += cost;
            if (surrogatePair) {
                i++;
            }
        }

        return -1;
    }

    /**
     * Index of the {@code n}-th newline, or {@code -1} if the text has fewer.
     *
     * <p>Used instead of splitting: a 10 MiB log is a quarter of a million lines,
     * and building that array only to throw it away costs more than the scan.
     */
    private static int indexOfNewline(String text, int n) {
        int at = -1;
        for (int seen = 0; seen < n; seen++) {
            at = text.indexOf('\n', at + 1);
            if (at < 0) {
                return -1;
            }
        }
        return at;
    }

    private static int countLines(String text) {
        if (text.isEmpty()) {
            return 0;
        }

        int lines = 1;
        for (int at = text.indexOf('\n'); at >= 0; at = text.indexOf('\n', at + 1)) {
            lines++;
        }
        return lines;
    }
}
