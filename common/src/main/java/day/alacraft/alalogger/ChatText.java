package day.alacraft.alalogger;

/**
 * Text that came from a server and is about to be printed in someone's chat.
 *
 * <p>Minecraft renders a section sign in a plain string as a formatting escape,
 * so text put into a component verbatim can paint itself, hide itself, or set
 * itself in the colours the game uses for its own messages. Everything this mod
 * prints from a finding — the message, the hint, the reason inside an error — is
 * a sentence the server chose, and {@code apiBaseUrl} is configurable, so "the
 * server" is not always ours.
 *
 * <p>That is the whole exposure: an operator pointed at somebody else's instance
 * can be shown a line that looks like the game speaking. Not dramatic, but it
 * costs one pass over a short string to remove, and the alternative is trusting
 * every instance anyone ever hosts.
 *
 * <p>The escape becomes an ampersand rather than disappearing, because a finding
 * that legitimately mentions a colour code should still be readable — and
 * because silently deleting characters out of somebody's error message is how a
 * mod gets blamed for mangling it.
 *
 * <p>One consequence is worth stating, because it is not what the class is for:
 * {@link day.alacraft.alalogger.api.ApiError} passes its message through here
 * too, and most of those are written by the mod out of an exception rather than
 * received from anyone. A TLS failure whose message spans two lines therefore
 * reaches the log as one. That is the right shape for a log line, but it is a
 * change to our own diagnostics and not only to somebody else's text.
 */
public final class ChatText {

    /**
     * What Minecraft reads as the start of a formatting code.
     *
     * <p>Written as an escape rather than as the character itself: this file is
     * published, and a lone non-ASCII byte in source is the kind of thing that
     * survives one editor and not the next.
     */
    private static final char FORMATTING_ESCAPE = '\u00a7';

    /**
     * The longest sentence worth putting in a chat line.
     *
     * <p>A limit rather than a preference. A chat component travels as NBT,
     * whose string tag carries its length in two unsigned bytes, so a component
     * over 65535 bytes cannot be encoded and the attempt throws on the thread
     * answering the command. The site's findings are one sentence; anything
     * approaching this is a broken or hostile instance, and the response cap of
     * one megabyte is far above what a chat line can hold.
     */
    private static final int MAX_LENGTH = 512;

    /** Marks a sentence that was cut, so nobody hunts for the missing half. */
    private static final char ELLIPSIS = '\u2026';

    private ChatText() {
    }

    /**
     * The same sentence, with nothing in it that can format a chat line.
     *
     * <p>Control characters go too: a newline in a literal splits one line into
     * two, which is enough to forge a message that looks like it came from
     * somewhere else, and a carriage return can hide what precedes it.
     */
    public static String plain(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }

        String bounded = bound(text);
        StringBuilder safe = null;

        for (int i = 0; i < bounded.length(); i++) {
            char c = bounded.charAt(i);
            char replacement = replacementFor(c);

            if (replacement == c) {
                if (safe != null) {
                    safe.append(c);
                }
                continue;
            }

            // Nothing is copied until something has to change, so the ordinary
            // case — every message the real site sends — allocates nothing.
            if (safe == null) {
                safe = new StringBuilder(bounded.length()).append(bounded, 0, i);
            }

            safe.append(replacement);
        }

        return safe == null ? bounded : safe.toString();
    }

    /**
     * Someone else's sentence, fitted into the middle of one of ours.
     *
     * <p>Error messages arrive already punctuated ("Log not found."), and the
     * templates that quote them supply their own full stop, so the naive
     * concatenation reads "Log not found.. Details are in the server log." —
     * seen in game on 2026-09-02. Trailing stops are dropped; a question or
     * exclamation mark stays, because removing those changes what the sentence
     * says.
     */
    public static String embedded(String text) {
        String safe = plain(text).strip();

        while (safe.endsWith(".")) {
            safe = safe.substring(0, safe.length() - 1).stripTrailing();
        }

        return safe;
    }

    private static char replacementFor(char c) {
        if (c == FORMATTING_ESCAPE) {
            return '&';
        }

        return invisible(c) ? ' ' : c;
    }

    /**
     * Characters that occupy no width and change how what follows is read.
     *
     * <p>Each range is here for a reason. C0 and DEL split a line in two, which
     * is enough to forge a message that looks like it came from somewhere else.
     * The C1 block contains U+0085, which several log readers treat as a line
     * break — and these sentences reach the log file as well as the chat. The
     * bidi overrides and isolates reverse the text after them, so a link can be
     * made to read as something it is not. The zero-width marks and the
     * byte-order mark are invisible by definition, which makes two different
     * strings look like one.
     */
    private static boolean invisible(char c) {
        return c < ' '
                || (c >= '\u007f' && c <= '\u009f')
                || (c >= '\u200b' && c <= '\u200f')
                || (c >= '\u2028' && c <= '\u202e')
                || (c >= '\u2066' && c <= '\u2069')
                || c == '\ufeff';
    }

    /**
     * The same text, short enough to survive being sent.
     *
     * <p>Cut before a surrogate pair rather than through one: half a pair is not
     * a character, and it would reach the wire as a replacement mark or an
     * encoding error rather than as the emoji somebody wrote.
     */
    private static String bound(String text) {
        if (text.length() <= MAX_LENGTH) {
            return text;
        }

        int cut = MAX_LENGTH - 1;

        if (Character.isHighSurrogate(text.charAt(cut - 1))) {
            cut--;
        }

        return text.substring(0, cut) + ELLIPSIS;
    }
}
