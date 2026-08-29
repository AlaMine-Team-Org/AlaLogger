package day.alacraft.alalogger.redact.rules;

import day.alacraft.alalogger.redact.RedactionPattern;
import day.alacraft.alalogger.redact.RegexRule;

import java.util.List;

/**
 * The operating-system account name, which leaks through file paths.
 *
 * <p>Every Minecraft crash report is full of paths like
 * {@code C:\Users\Ivan\AppData\Roaming\.minecraft\…}, so sharing one raw
 * publishes the player's real first name more often than not.
 *
 * <p>The first five patterns exist because Windows appears in three different
 * escapings depending on which component wrote the line — plain, JSON-escaped
 * double backslashes, and forward slashes — and missing any one of them leaks
 * the name anyway.
 *
 * <p>All five require a <b>trailing separator</b>: {@code /home/name/}, not
 * {@code /home/name}. That is fine for a path pointing at a file and useless for
 * the places where a home directory is the whole value — {@code HOME=/home/artem},
 * {@code user.home = C:\Users\User}, {@code Working Directory: /home/artem}. A JVM
 * fatal-error file prints an {@code Environment Variables:} block made almost
 * entirely of that shape, so the last five patterns repeat each one with an
 * end-of-token boundary instead. Closing that hole is most of the reason this
 * rule is worth porting rather than trusting the site to catch it later.
 *
 * <p>Note what is deliberately <em>not</em> here: the player's Minecraft name.
 * It is public — anyone can look it up from a server list — and a log where the
 * nickname has been blanked out is much harder to read.
 */
public final class UserPathRule extends RegexRule {

    /** Placeholder, matching the site's so both ends produce identical text. */
    private static final String MASK = "********";

    private static final List<RedactionPattern> PATTERNS = List.of(

            // ── paths with a trailing separator ──

            // Windows, plain:  C:\Users\Name\
            // regex: [A-Za-z]:\\Users\\[^\\/\r\n"]+\\
            RedactionPattern.of(
                    "[A-Za-z]:\\\\Users\\\\[^\\\\/\\r\\n\"]+\\\\",
                    "C:\\Users\\" + MASK + "\\",
                    "\\Users\\"),

            // Windows, JSON-escaped:  C:\\Users\\Name\\
            // regex: [A-Za-z]:\\\\Users\\\\[^\\/\r\n"]+\\\\
            RedactionPattern.of(
                    "[A-Za-z]:\\\\\\\\Users\\\\\\\\[^\\\\/\\r\\n\"]+\\\\\\\\",
                    "C:\\\\Users\\\\" + MASK + "\\\\",
                    "\\\\Users\\\\"),

            // Windows, forward slashes:  C:/Users/Name/
            RedactionPattern.of(
                    "[A-Za-z]:/Users/[^/\\r\\n\"]+/",
                    "C:/Users/" + MASK + "/",
                    ":/Users/"),

            // Linux:  /home/name/
            // The lookbehind keeps the pattern out of URLs and out of longer
            // words that merely end in "home".
            RedactionPattern.of(
                    "(?<!\\w)/home/[^/\\r\\n\"]+/",
                    "/home/" + MASK + "/",
                    "/home/"),

            // macOS:  /Users/name/
            RedactionPattern.of(
                    "(?<!\\w)/Users/[^/\\r\\n\"]+/",
                    "/Users/" + MASK + "/",
                    "/Users/"),

            // ── the Environment Variables block of a JVM crash file ──

            RedactionPattern.of("\\bUSERNAME=[^\\s\\r\\n]+", "USERNAME=" + MASK, "USERNAME="),

            // Windows contributes USERPROFILE/USERDOMAIN/COMPUTERNAME/LOGONSERVER,
            // Linux and macOS contribute USER/LOGNAME/HOSTNAME — between them the
            // account name, the machine name, and the domain it is joined to.
            //
            // \bUSER= cannot swallow USERNAME=: the "=" has to follow immediately,
            // so the longer name simply fails to match.
            RedactionPattern.of("\\bUSER=[^\\s\\r\\n]+", "USER=" + MASK, "USER="),
            RedactionPattern.of("\\bLOGNAME=[^\\s\\r\\n]+", "LOGNAME=" + MASK, "LOGNAME="),
            RedactionPattern.of("\\bUSERDOMAIN=[^\\s\\r\\n]+", "USERDOMAIN=" + MASK, "USERDOMAIN="),
            RedactionPattern.of("\\bHOSTNAME=[^\\s\\r\\n]+", "HOSTNAME=" + MASK, "HOSTNAME="),
            RedactionPattern.of("\\bCOMPUTERNAME=[^\\s\\r\\n]+", "COMPUTERNAME=" + MASK, "COMPUTERNAME="),
            RedactionPattern.of("\\bLOGONSERVER=[^\\s\\r\\n]+", "LOGONSERVER=" + MASK, "LOGONSERVER="),

            // USERPROFILE takes the whole line rather than the first token: its
            // value is always a filesystem path, Windows account folders really do
            // contain spaces ("C:\Users\John Smith"), and the variable only ever
            // appears as one line of an environment dump.
            RedactionPattern.of("\\bUSERPROFILE=[^\\r\\n]+", "USERPROFILE=" + MASK, "USERPROFILE="),

            // System.getProperties() dumps, printed by several mods and some
            // launchers. Bounded to one token so a properties map printed on a
            // single line is not eaten whole; the separator class excludes
            // newlines so a trailing "user.home" cannot swallow the line below.
            RedactionPattern.of("user\\.name[ \\t]*[:=][ \\t]*\\S+", "user.name=" + MASK, "user.name"),
            RedactionPattern.of("user\\.home[ \\t]*[:=][ \\t]*\\S+", "user.home=" + MASK, "user.home"),

            // ── the same paths again, without the trailing separator ──
            //
            // These stop at the first whitespace, quote or line end instead of
            // requiring another separator, which is what catches HOME=/home/artem.
            // The token cannot contain a space, so an account folder named
            // "John Smith" is only masked as far as "John" — the alternative,
            // running to the end of the line, would swallow ordinary prose that
            // happens to follow a path, and over-redacting the body of the log is
            // a worse default than a partially masked surname.
            //
            // "$" is a backstop only: in a multi-line log the newline after the
            // path already satisfies [\s"'].
            RedactionPattern.of(
                    "[A-Za-z]:\\\\Users\\\\[^\\\\/\\s\"']+(?=[\\s\"']|$)",
                    "C:\\Users\\" + MASK,
                    "\\Users\\"),
            RedactionPattern.of(
                    "[A-Za-z]:\\\\\\\\Users\\\\\\\\[^\\\\/\\s\"']+(?=[\\s\"']|$)",
                    "C:\\\\Users\\\\" + MASK,
                    "\\\\Users\\\\"),
            RedactionPattern.of(
                    "[A-Za-z]:/Users/[^/\\s\"']+(?=[\\s\"']|$)",
                    "C:/Users/" + MASK,
                    ":/Users/"),
            RedactionPattern.of(
                    "(?<!\\w)/home/[^/\\s\"']+(?=[\\s\"']|$)",
                    "/home/" + MASK,
                    "/home/"),
            RedactionPattern.of(
                    "(?<!\\w)/Users/[^/\\s\"']+(?=[\\s\"']|$)",
                    "/Users/" + MASK,
                    "/Users/"));

    @Override
    public String key() {
        return "path";
    }

    @Override
    protected List<RedactionPattern> patterns() {
        return PATTERNS;
    }
}
