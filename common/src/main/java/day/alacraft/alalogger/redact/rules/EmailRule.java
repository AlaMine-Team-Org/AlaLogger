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
}
