package day.alacraft.alalogger.redact;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Base for rules that replace regex matches with a fixed mask.
 *
 * <p>Three mechanics. Two of them are non-obvious and surface only in
 * production, which is why they are spelled out here rather than left to be
 * rediscovered:
 *
 * <ul>
 *   <li><b>Exemptions.</b> A match that also matches an exemption is left alone.
 *       Masking {@code 127.0.0.1} or {@code 8.8.8.8} destroys useful diagnostics
 *       while protecting nobody.
 *   <li><b>Honest counting.</b> Replacements are counted per rule so the player
 *       is told what was stripped rather than having the log quietly rewritten.
 *   <li><b>No-op replacements do not count.</b> Two patterns in the same rule can
 *       legitimately match the same text — {@code C:/Users/Bob/} is masked by the
 *       Windows pattern, and the masked result is then a valid match for the
 *       macOS one, which rewrites it to exactly the same string. The second
 *       replacement changes nothing, so counting it would inflate "we removed N
 *       paths" with values that were already gone.
 * </ul>
 *
 * <p>Subclasses expose their patterns from a {@code static final} list.
 * {@link Pattern#compile} is not cheap and this runs on a game thread, so a rule
 * that rebuilt its patterns per call would pay for every upload.
 */
public abstract class RegexRule implements RedactionRule {

    /** Patterns to apply, in order. Order matters: see the class docs. */
    protected abstract List<RedactionPattern> patterns();

    /** Patterns that make a matched <em>value</em> exempt from replacement. */
    protected List<Pattern> exemptions() {
        return List.of();
    }

    @Override
    public RuleOutcome apply(String content) {
        int count = 0;

        for (RedactionPattern rule : patterns()) {
            // Cheap literal test first — most patterns cannot match most logs,
            // and this is what keeps a 10 MiB file well inside a second.
            if (!rule.mightMatch(content)) {
                continue;
            }

            Matcher matcher = rule.pattern().matcher(content);
            if (!matcher.find()) {
                // Nothing to rewrite: keep the same String instance rather than
                // rebuilding an identical one.
                continue;
            }

            // Built by hand instead of Matcher.appendReplacement because the
            // replacements are literals containing backslashes ("C:\Users\…"),
            // which appendReplacement would read as escape sequences. Escaping
            // them at every call site is exactly the kind of detail that goes
            // wrong once and leaks a path.
            StringBuilder out = new StringBuilder(content.length() + 32);
            int copiedUpTo = 0;

            do {
                String match = matcher.group();
                out.append(content, copiedUpTo, matcher.start());

                if (isExempt(content, matcher.start(), match)) {
                    out.append(match);
                } else {
                    out.append(rule.replacement());
                    if (!match.equals(rule.replacement())) {
                        count++;
                    }
                }

                copiedUpTo = matcher.end();
            } while (matcher.find());

            out.append(content, copiedUpTo, content.length());
            content = out.toString();
        }

        return new RuleOutcome(content, count);
    }

    /**
     * Whether this match must be left alone.
     *
     * <p>Takes the whole text and the offset as well as the match so a rule can
     * look at the surrounding line — {@link day.alacraft.alalogger.redact.rules.IpAddressRule}
     * needs that to tell a mod version from an address.
     */
    protected boolean isExempt(String content, int start, String match) {
        for (Pattern exemption : exemptions()) {
            if (exemption.matcher(match).find()) {
                return true;
            }
        }
        return false;
    }
}
