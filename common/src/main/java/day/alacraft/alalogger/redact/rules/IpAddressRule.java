package day.alacraft.alalogger.redact.rules;

import day.alacraft.alalogger.redact.RedactionPattern;
import day.alacraft.alalogger.redact.RegexRule;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Player and server IP addresses.
 *
 * <p>A Minecraft server prints a joining player's address on every connect
 * ({@code Player123[/203.0.113.7:51234] logged in}), so sharing a log unfiltered
 * publishes the home address of everyone who was online. This is the rule that
 * fires most often in practice.
 *
 * <p>Two details of the IPv4 pattern are the product of real-world use and are
 * easy to get wrong from scratch: the {@code version} lookbehinds stop
 * {@code version 1.20.1} from being masked, and localhost plus the well-known
 * public resolvers are exempt because masking them removes diagnostic value and
 * protects nobody.
 */
public final class IpAddressRule extends RegexRule {

    private static final List<RedactionPattern> PATTERNS = List.of(
            // IPv4. The assertions are ordered cheapest-first on purpose: this
            // pattern has no literal prefix, so Java walks it over every
            // character of the log, and the one-character lookbehind rejects
            // most positions before the version literals are ever compared.
            //
            // The version guard reads naturally as a single (?<!version:? )
            // group, but that is a variable-length lookbehind and java.util.regex
            // rejects it. Split into two fixed-length lookbehinds, which is
            // equivalent.
            //
            // The trailing (?!-[A-Za-z]) was added after checking three real
            // Minecraft 26.x crash reports: every
            // IPv4-shaped token in all three was "26.1.2.28-beta", the NeoForge
            // build version, appearing on dozens of stack-trace frames
            // ("TRANSFORMER/neoforge@26.1.2.28-beta/…"), in the mod table and on
            // the "NeoForge:" line. All four parts are below 256, so the value
            // exemptions below do not help, and the frames are ordinary log lines,
            // so no line-level guard can help either — without this, sharing a
            // NeoForge crash report mangles most of its stack trace.
            //
            // A pre-release suffix always starts with a letter (-beta, -rc1,
            // -SNAPSHOT, -universal); the one thing that could follow a real
            // address with a hyphen is another address, as in a range, and that
            // starts with a digit. So the discriminator is the letter, not the
            // hyphen.
            //
            // regex: (?<![\w-])(?<!version )(?<!version: )(?:[0-9]{1,3}\.){3}[0-9]{1,3}(?![0-9])(?!-[A-Za-z])
            RedactionPattern.of(
                    "(?<![\\w-])(?<!version )(?<!version: )(?:[0-9]{1,3}\\.){3}[0-9]{1,3}(?![0-9])(?!-[A-Za-z])",
                    "***.***.***.***"),

            // IPv6, full and compressed. Deliberately narrow: at least three
            // colon-separated groups plus a tail, so the "12:34:56" in a log
            // timestamp prefix is not touched. The bounded {1,4} groups also cap
            // backtracking on the hex-heavy lines of a JVM crash file.
            RedactionPattern.of(
                    "(?<![0-9A-Fa-f:])(?:[0-9A-Fa-f]{1,4}:){3,7}[0-9A-Fa-f]{1,4}(?![0-9A-Fa-f:])",
                    "[ipv6-removed]"));

    private static final List<Pattern> EXEMPTIONS = List.of(
            Pattern.compile("^127\\.[0-9]{1,3}\\.[0-9]{1,3}\\.[0-9]{1,3}$"), // loopback
            Pattern.compile("^0\\.0\\.0\\.0$"),                              // "bind to all"
            Pattern.compile("^1\\.[01]\\.[01]\\.1$"),                        // Cloudflare DNS
            Pattern.compile("^8\\.8\\.[84]\\.[84]$"),                        // Google DNS
            Pattern.compile("^(?:::1|::)$"),                                 // IPv6 loopback / unspecified

            // Any group above 255 cannot be an address, so it is a version
            // number that happens to have four parts — a GPU driver string like
            // "DriverVersion=31.0.15.3623". Masking those destroys the single
            // most useful line of a graphics crash and protects nobody.
            Pattern.compile("(?:^|\\.)(?:2[6-9][0-9]|[3-9][0-9]{2}|[0-9]{4,})(?:\\.|$)"));

    /**
     * Mod-list entries, where a four-part version is indistinguishable from an
     * address.
     *
     * <p>{@code jei: Just Enough Items 15.2.0.27} has four parts all below 256,
     * so no pattern can tell it from an IP — and the installed mod list is the
     * most useful part of a modded crash report. Rather than lose it, addresses
     * are simply not masked on indented {@code modid: Name Version} lines, where
     * a real address does not occur.
     *
     * <p>The alternative seen elsewhere is to rewrite the dots in a version to
     * U+2219 before upload, so that a server-side filter misses them. That
     * corrupts the text in order to protect it; this guard does not.
     */
    private static final Pattern MOD_LIST_LINE = Pattern.compile("\\s{2,}[\\w.\\-]+:\\s+\\S");

    /**
     * The other mod-list format, and the one Forge and NeoForge actually use:
     * an indented jar name followed by pipe-delimited columns.
     *
     * <pre>
     *         jei-15.2.0.27.jar   |Just Enough Items   |jei   |15.2.0.27   |Manifest: …
     * </pre>
     *
     * <p>{@link #MOD_LIST_LINE} matches the {@code modid: Name Version} shape
     * Fabric prints. On a NeoForge report, which is the majority of modded crashes, it matches
     * nothing at all and a four-part mod version in the version column is masked
     * as an address. A row of this table cannot contain a player's address, so
     * skipping the whole line is safe here in a way that skipping every indented
     * {@code key: value} line would not be — a network protocol error report puts
     * the server address on exactly that shape of line.
     */
    private static final Pattern MOD_TABLE_LINE = Pattern.compile("\\s+\\S+\\.jar\\s*\\|");

    /**
     * The loaded-library inventory a Minecraft client crash report prints, one
     * row per native library:
     *
     * <pre>
     *         jvm.dll:OpenJDK 64-Bit server VM:25.0.1.0:Microsoft
     * </pre>
     *
     * <p>Found by running this rule over three real crash reports from this
     * machine: the client one came back with thirteen library versions rewritten
     * as addresses, all of them {@code 25.0.1.0} — four parts, every part below
     * 256, so the value exemptions do not fire. {@link #MOD_LIST_LINE} does not
     * cover the row because there is no space after the colon.
     *
     * <p>Worth keeping: this section is how an injected overlay, anti-cheat or
     * RGB-utility DLL gets identified as the cause of a crash, and a version is
     * usually the deciding detail.
     */
    private static final Pattern LIBRARY_LINE =
            Pattern.compile("\\s{2,}[\\w.\\-]+\\.(?:dll|exe|so|dylib)\\b", Pattern.CASE_INSENSITIVE);

    /**
     * The third mod-list format, and the one a NeoForge client prints at startup:
     * an indented display name, the version, and the mod id in brackets.
     *
     * <pre>
     *         Ala Logger 0.1.0 (alalogger)
     *         Minecraft 26.2 (minecraft)
     *         NeoForge 26.2.0.67 (neoforge)
     * </pre>
     *
     * <p>Found by running the mod on NeoForge and reading what it published:
     * {@code NeoForge 26.2.0.67 (neoforge)} came back as
     * {@code NeoForge ***.***.***.*** (neoforge)}. Four parts, all below 256 and
     * no trailing suffix, so neither the value exemptions nor the {@code -beta}
     * guard applies - and the other two line guards do not match this shape,
     * because there is no colon and no {@code .jar |} column.
     *
     * <p>Worth its own pattern because of WHERE it appears. This block is the
     * header of the log, the first thing anyone reads, and the loader version is
     * the most useful line in it. Losing it costs more than every other false
     * positive here combined: the person helping cannot even tell which platform
     * the log came from.
     *
     * <p>Bounded deliberately: the version has to start with a digit and the
     * brackets have to close at the end of the line, which is what keeps an
     * ordinary sentence ending in a parenthetical from passing as a mod row.
     */
    private static final Pattern MOD_ID_LINE =
            Pattern.compile("\\s{2,}\\S.*\\s[0-9][\\w.+\\-]*\\s+\\([a-z0-9_.\\-]+\\)\\s*$");

    @Override
    public String key() {
        return "ip";
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
        return isOnModListLine(content, start) || super.isExempt(content, start, match);
    }

    /**
     * Whether the line holding this match looks like a mod-list entry.
     *
     * <p>Checked here, per match, rather than by splitting the log into lines and
     * filtering: matches are rare and lines are not, so this walks a few
     * characters a handful of times instead of allocating a quarter of a million
     * substrings for a 10 MiB log.
     */
    private static boolean isOnModListLine(String content, int start) {
        int lineStart = content.lastIndexOf('\n', start) + 1;
        int lineEnd = content.indexOf('\n', start);
        if (lineEnd < 0) {
            lineEnd = content.length();
        }

        // Anchored at the region start by lookingAt(); the region end keeps
        // \s from running past the line break into the next line.
        Matcher matcher = MOD_LIST_LINE.matcher(content).region(lineStart, lineEnd);
        if (matcher.lookingAt()) {
            return true;
        }
        if (matcher.usePattern(MOD_TABLE_LINE).lookingAt()) {
            return true;
        }

        if (matcher.usePattern(LIBRARY_LINE).lookingAt()) {
            return true;
        }

        return matcher.usePattern(MOD_ID_LINE).lookingAt();
    }
}
