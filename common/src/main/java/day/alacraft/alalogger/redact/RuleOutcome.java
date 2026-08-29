package day.alacraft.alalogger.redact;

/**
 * What one rule did to the text: the rewritten content and how many values it
 * actually replaced.
 *
 * @param content the text after this rule ran
 * @param count   number of values replaced — the honest count, not the number of
 *                regex hits: a match that was exempt, or that was already masked,
 *                is not counted (see {@link RegexRule})
 */
public record RuleOutcome(String content, int count) {

    /** Nothing matched — the common case, and the one that must not allocate. */
    public static RuleOutcome unchanged(String content) {
        return new RuleOutcome(content, 0);
    }
}
