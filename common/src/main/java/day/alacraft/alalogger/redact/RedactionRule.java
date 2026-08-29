package day.alacraft.alalogger.redact;

/**
 * One class of sensitive data removed from a log <em>before it leaves the
 * player's machine</em>.
 *
 * <p>This is what sets Ala Logger apart. The usual approach is to upload the file
 * as it is and rely on the receiving site to clean it, which means the raw session
 * token crosses the network and lands in someone else's request log on the way.
 * Here the rules run locally, in the game's own JVM, and the bytes that go out are
 * already clean.
 *
 * <p>The same rules also run again server-side on alacraft.day (see
 * {@code app/Services/LogRedaction} in the site repository, which this package is
 * a port of). That is deliberate belt-and-braces: an old mod build, a log pasted
 * by hand into the web form, or a third-party integration all have to end up
 * equally clean. A rule must therefore never assume the other side did anything.
 */
public interface RedactionRule {

    /**
     * Stable machine key, shared with the site so both ends name the same thing.
     *
     * <p>It is what the upload reports as {@code redaction_summary}, what
     * {@code GET /api/v1/logs/filters} lists, and the suffix of the translation
     * key the chat message uses ({@code messages.views.logs.redaction.{key}}).
     * Changing one is a protocol change, not a rename.
     */
    String key();

    /**
     * Redact every match in {@code content}.
     *
     * <p>Returns the rewritten text together with how many replacements were
     * made, so the player can be told exactly what was stripped ("2 IP addresses,
     * 1 access token") instead of having their log silently rewritten.
     */
    RuleOutcome apply(String content);
}
