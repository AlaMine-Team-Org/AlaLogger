package day.alacraft.alalogger.redact.rules;

import day.alacraft.alalogger.redact.RedactionRule;
import day.alacraft.alalogger.redact.RuleOutcome;

/**
 * Raw memory dumps inside a JVM fatal-error file ({@code hs_err_pid*.log}).
 *
 * <p>When the JVM dies on a signal it writes several blocks of the process's own
 * memory into the report — {@code Register to memory mapping:},
 * {@code Top of Stack:}, {@code Instructions:}, {@code [MachCode]} — as hex, and
 * on some platforms with an ASCII column beside it. Whatever happened to be in
 * those pages goes with it: chat text, a buffered HTTP body, a session token the
 * garbage collector had not reached yet.
 *
 * <p>No regex can clean that. A pattern can only mask a shape it recognises, and
 * the whole problem here is that the content is arbitrary. The only honest option
 * is to cut the blocks out — which is what makes "the safe place to share an
 * hs_err file" a true statement rather than an aspiration, and it costs nothing a
 * player needs: the {@code Problematic frame}, the stacks, the thread list, the
 * loaded-module list and every other text section are kept. Those are what the
 * diagnosis is actually made from.
 *
 * <p>Unlike the other rules this one is context-gated. {@code Instructions:} and
 * {@code Registers:} are ordinary words a mod could print for its own reasons, so
 * the cut only happens in a file that identifies itself as a JVM crash.
 *
 * <p>Its {@link #apply} count is a number of <em>sections</em> removed, not lines
 * — "3 memory dumps removed" is what a player can act on.
 */
public final class JvmMemoryDumpRule implements RedactionRule {

    /**
     * The first line of every hs_err file, and the only reliable way to tell one
     * from a Minecraft crash report — the JVM writes it verbatim on every
     * platform and every vendor build.
     */
    public static final String HS_ERR_MARKER =
            "A fatal error has been detected by the Java Runtime Environment";

    /**
     * Section headers whose body is raw memory. The header line itself is kept:
     * it names the program counter or stack pointer the dump was taken from,
     * which is useful, and it holds no memory contents of its own.
     */
    private static final String[] DUMP_HEADERS = {
            "Registers:",
            "Register to memory mapping:",
            "Top of Stack:",
            "Instructions:",
            "[MachCode]",
    };

    private static final String MARKER_SUFFIX = " lines removed for safety]";

    @Override
    public String key() {
        return "memdump";
    }

    @Override
    public RuleOutcome apply(String content) {
        if (!content.contains(HS_ERR_MARKER)) {
            return RuleOutcome.unchanged(content);
        }

        String[] lines = content.split("\n", -1);
        StringBuilder out = new StringBuilder(content.length());

        int sections = 0;
        int written = 0;
        int i = 0;

        while (i < lines.length) {
            String line = lines[i];
            if (written++ > 0) {
                out.append('\n');
            }
            out.append(line);
            i++;

            if (!isDumpHeader(line)) {
                continue;
            }

            boolean machCode = line.trim().equals("[MachCode]");

            // "Register to memory mapping:" puts a blank line between the header
            // and its body, so blank lines directly under a header belong to it —
            // stopping at the first blank line would cut nothing at all.
            while (i < lines.length && lines[i].isBlank()) {
                i++;
            }

            int removed = 0;
            while (i < lines.length) {
                String body = lines[i];

                if (isDumpHeader(body) || isSectionBanner(body) || isRemovalMarker(body)) {
                    break;
                }

                // A MachCode block is delimited, not blank-line separated, and it
                // can contain blank lines between its sub-blocks. Stopping at the
                // first one would leave the rest of the disassembly in place.
                if (!machCode && body.isBlank()) {
                    break;
                }

                i++;
                removed++;

                if (machCode && body.trim().equals("[/MachCode]")) {
                    break;
                }
            }

            if (removed == 0) {
                continue;
            }

            sections++;
            if (written++ > 0) {
                out.append('\n');
            }
            out.append('[').append(removed).append(MARKER_SUFFIX);
        }

        return new RuleOutcome(out.toString(), sections);
    }

    private static boolean isDumpHeader(String line) {
        String trimmed = line.trim();

        for (String header : DUMP_HEADERS) {
            // "Top of Stack:" and "Instructions:" carry a suffix — the sp or pc
            // they were dumped from — so headers are matched by prefix.
            if (trimmed.startsWith(header)) {
                return true;
            }
        }

        return false;
    }

    /**
     * {@code ---------------  P R O C E S S  ---------------} and friends.
     *
     * <p>A backstop for a {@code [MachCode]} block whose closing tag never
     * arrives, which is exactly what a file truncated by the crash itself looks
     * like: without this, everything after the header would be removed.
     */
    private static boolean isSectionBanner(String line) {
        return line.stripLeading().startsWith("-----");
    }

    /**
     * A marker this rule already wrote, so running twice over the same text is a
     * no-op rather than a rule that eats its own output and reports "1 line
     * removed" where the first pass removed two hundred.
     *
     * <p>Relevant because the log is cleaned here and then cleaned again on
     * arrival at alacraft.day. The site's copy of this rule has no equivalent
     * guard yet, so its second pass reports "1 line removed" over a marker this
     * one wrote; harmless, and the same three lines fix it there.
     */
    private static boolean isRemovalMarker(String line) {
        String trimmed = line.trim();
        return trimmed.startsWith("[") && trimmed.endsWith(MARKER_SUFFIX);
    }
}
