package day.alacraft.alalogger.redact.rules;

import day.alacraft.alalogger.redact.RedactionPattern;
import day.alacraft.alalogger.redact.RegexRule;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Email addresses — they reach logs through Mojang account errors, plugin licence
 * checks and SMTP notification plugins.
 *
 * <p>The domain has to end in at least two letters, which is what keeps the
 * pattern away from the {@code @} that appears on nearly every line of a modded
 * stack trace: {@code at TRANSFORMER/minecraft@26.1.2/net.minecraft.…} has no
 * letters after its final dot, so it is not an address.
 *
 * <p>That alone is not enough. Netty versions end in letters
 * ({@code 4.2.16.Final}), so a frame such as
 * {@code at SECURE-BOOTSTRAP/io.netty.common@4.2.16.Final/io.netty.util…} has the
 * shape of an address and came back as {@code [email-removed]} - found on a real
 * Forge 26.3 log, and the same frames appear on every loader. A match sitting in
 * the module slot of a frame, followed by the {@code /} that ends the slot, is
 * kept: an address cannot appear there.
 */
public final class EmailRule extends RegexRule {

    private static final List<RedactionPattern> PATTERNS = List.of(
            RedactionPattern.of(
                    "\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}\\b",
                    "[email-removed]",
                    "@"));

    private static final List<Pattern> EXEMPTIONS = List.of(
            // Mod and plugin authors put contact addresses in the metadata that
            // gets printed on startup, and example.com is all over shipped
            // configs. Neither belongs to the person sharing the log.
            Pattern.compile("@example\\.(?:com|org|net)$", Pattern.CASE_INSENSITIVE));

    /**
     * What a stack frame prints before its module slot: indentation, {@code at},
     * then an optional class-loader name and slash - the JDK's own
     * {@code StackTraceElement#toString} format.
     */
    private static final Pattern STACK_FRAME_PREFIX =
            Pattern.compile("\\s*at\\s+(?:[\\w.$\\-]+/)?");

    @Override
    public String key() {
        return "email";
    }

    @Override
    protected List<RedactionPattern> patterns() {
        return PATTERNS;
    }

    @Override
    protected List<Pattern> exemptions() {
        return EXEMPTIONS;
    }

    @Override
    protected boolean isExempt(String content, int start, String match) {
        return super.isExempt(content, start, match) || isStackFrameModule(content, start, match);
    }

    private static boolean isStackFrameModule(String content, int start, String match) {
        int end = start + match.length();
        if (end >= content.length() || content.charAt(end) != '/') {
            return false;
        }

        int lineStart = content.lastIndexOf('\n', start - 1) + 1;
        return STACK_FRAME_PREFIX.matcher(content).region(lineStart, start).matches();
    }
}
