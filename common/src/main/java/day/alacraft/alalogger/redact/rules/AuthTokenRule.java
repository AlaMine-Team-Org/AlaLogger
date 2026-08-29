package day.alacraft.alalogger.redact.rules;

import day.alacraft.alalogger.redact.RedactionPattern;
import day.alacraft.alalogger.redact.RegexRule;

import java.util.List;

/**
 * Minecraft / Mojang / Microsoft session credentials — the one thing in a log
 * that can cost someone their account.
 *
 * <p>The launcher writes the session token into the client log and into the
 * command line it prints on startup, so an unfiltered paste hands over the
 * account itself. Modrinth's support page puts it plainly: never share an unedited
 * JVM crash log with anyone. That is the gap this mod exists to close: a tool
 * that uploads the file untouched sends the token across the network first and
 * cleans it afterwards, if at all.
 *
 * <p>A JVM fatal-error file prints that command line <b>twice</b> — once as
 * {@code Command Line:} in the SUMMARY section near the top, and again as
 * {@code java_command:} under VM Arguments. Every pattern here is context-free on
 * purpose: a rule that only cleaned the "VM Arguments" block would publish the
 * first copy verbatim.
 *
 * <p>The argument separator is deliberately loose ({@code [ 	=:,]+}): launchers
 * write {@code --accessToken} followed by a space, an equals sign, a colon or a
 * comma depending on which one and which version wrote the line.
 */
public final class AuthTokenRule extends RegexRule {

    private static final List<RedactionPattern> PATTERNS = List.of(
            RedactionPattern.of(
                    "\\(Session ID is token:[^:)]+:[^)]+\\)",
                    "(Session ID is token:****:****)",
                    "Session ID is token:"),

            // Launch arguments, one pattern per flag because the replacement is a
            // literal and keeping the flag name visible is what makes the redacted
            // line still readable.
            //
            // The separator class is [ \t=:,] rather than [\s=:,]: launchers print
            // "--accessToken eyJ…", "--accessToken=eyJ…" and the comma-joined
            // argument-array form "--accessToken, eyJ…", but all of them on ONE
            // line. Allowing \s would let a line that merely ENDS on the flag name
            // swallow the first word of the next line and collapse the newline —
            // corrupting the log to protect nothing. A value that really did land
            // on the next line is still a JWT, and the signature pattern at the
            // bottom of this list catches it.
            RedactionPattern.of("--accessToken[ \\t=:,]+[^\\s,]+", "--accessToken ****", "--accessToken"),

            // Legacy "--session token:<token>:<uuid>" — same account access.
            RedactionPattern.of("--session[ \\t=:,]+[^\\s,]+", "--session ****", "--session"),

            // Xbox user id and launcher telemetry id. Neither helps diagnose
            // anything, and each identifies the account as surely as the token.
            RedactionPattern.of("--xuid[ \\t=:,]+[^\\s,]+", "--xuid ****", "--xuid"),
            RedactionPattern.of("--clientId[ \\t=:,]+[^\\s,]+", "--clientId ****", "--clientId"),

            RedactionPattern.of("\"accessToken\"\\s*:\\s*\"[^\"]+\"", "\"accessToken\":\"****\"", "accessToken"),
            RedactionPattern.of("\"authToken\"\\s*:\\s*\"[^\"]+\"", "\"authToken\":\"****\"", "authToken"),
            RedactionPattern.of("\"refreshToken\"\\s*:\\s*\"[^\"]+\"", "\"refreshToken\":\"****\"", "refreshToken"),
            RedactionPattern.of("\"access_token\"\\s*:\\s*\"[^\"]+\"", "\"access_token\":\"****\"", "access_token"),

            // Everything above needs the token to be labelled. These two do not,
            // and that is the point: a launcher or an auth plugin will happily
            // print a bare token mid-sentence, with no label in front of it.
            RedactionPattern.ofIgnoreCase("\\bBearer\\s+[A-Za-z0-9._~+/-]{8,}=*", "Bearer ****", "Bearer"),

            // Any JWT, whatever the surrounding prose. "eyJ" is base64 for '{"',
            // the opening of every JWT header, so the signature is specific enough
            // to mask on sight — nothing else in a Minecraft log looks like it, and
            // a Microsoft/Xbox session token that reaches a log IS this shape.
            RedactionPattern.of(
                    "\\beyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}(?:\\.[A-Za-z0-9_-]+)?",
                    "[token-removed]",
                    "eyJ"));

    @Override
    public String key() {
        return "token";
    }

    @Override
    protected List<RedactionPattern> patterns() {
        return PATTERNS;
    }
}
