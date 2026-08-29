package day.alacraft.alalogger.redact.rules;

import day.alacraft.alalogger.redact.RedactionPattern;
import day.alacraft.alalogger.redact.RegexRule;

import java.util.List;

/**
 * Third-party API credentials that end up in server logs.
 *
 * <p>Log-sharing tools usually cover IP addresses, session tokens and user paths
 * and stop there. But a modded server log
 * routinely carries a Discord webhook (chat bridges), a Telegram bot token
 * (notification plugins) or an AWS key (backup plugins), and a leaked webhook is
 * enough to spam or impersonate on someone's server.
 *
 * <p>The goal is not to catch every possible secret — that is unachievable — but
 * to cover the formats that actually show up. New formats are added as they are
 * observed.
 */
public final class SecretKeyRule extends RegexRule {

    private static final List<RedactionPattern> PATTERNS = List.of(
            // Discord webhook — the whole URL, because the URL *is* the credential.
            RedactionPattern.ofIgnoreCase(
                    "https?://(?:canary\\.|ptb\\.)?discord(?:app)?\\.com/api/webhooks/\\S+",
                    "[discord-webhook-removed]",
                    "discord"),

            // Discord bot token: base64 id . timestamp . hmac
            RedactionPattern.of(
                    "\\b[A-Za-z0-9_-]{23,28}\\.[A-Za-z0-9_-]{6,7}\\.[A-Za-z0-9_-]{27,40}\\b",
                    "[discord-token-removed]"),

            // Telegram bot token: numeric id : 35-character secret
            RedactionPattern.of(
                    "\\b[0-9]{8,12}:[A-Za-z0-9_-]{35}\\b",
                    "[telegram-token-removed]"),

            // Credentials inside a URL: https://user:password@host/…
            //
            // This one is close to home. A self-hosted instance configured as
            // https://user:pass@logs.example/api/v1 puts that password into
            // latest.log the moment anything prints the base URL — and
            // latest.log is precisely the file this mod uploads. Without this
            // pattern the tool for publishing logs safely would publish a
            // password it wrote there itself.
            RedactionPattern.of(
                    "(?<=://)[^/\\s:@]+:[^/\\s@]+(?=@)",
                    "[credentials-removed]"),

            // This mod's own API token (Laravel Sanctum: id|40+ characters).
            //
            // Every other rule here protects somebody else's secret. A plugin
            // dumping its config, a -D flag, a stack trace carrying a file's
            // contents — any of those can put our token in the log, and sending
            // it to a self-hosted instance means handing it to a third party.
            RedactionPattern.of(
                    "\\b[0-9]+\\|[A-Za-z0-9]{40,}\\b",
                    "[api-token-removed]"),

            // AWS access key id, and the secret when it is labelled.
            RedactionPattern.of("\\b(?:AKIA|ASIA)[0-9A-Z]{16}\\b", "[aws-key-removed]", "AKIA", "ASIA"),
            RedactionPattern.ofIgnoreCase(
                    "\\baws_secret_access_key\\s*[:=]\\s*\\S+",
                    "aws_secret_access_key=[removed]",
                    "aws_secret_access_key"),

            // Generic labelled credentials: api_key: "…", secret_key = …, client_secret=…
            RedactionPattern.ofIgnoreCase(
                    "\\b(?:api[_-]?key|apikey|secret[_-]?key|client[_-]?secret)\\s*[:=]\\s*[\"']?[A-Za-z0-9_\\-.]{16,}[\"']?",
                    "api_key=[removed]",
                    "api", "secret"),

            // Passwords, including the ones that carry a prefix. A plain \bpassword
            // never matched "proxyPassword=" or "dbPassword=" — there is no word
            // boundary in the middle of a word — so a -Dhttp.proxyPassword= JVM
            // flag, which is exactly what a crash report prints back at you, went
            // through untouched.
            //
            // The prefix is bounded and anchored with a lookbehind rather than
            // written as an open-ended [\w.]*: an unbounded prefix makes the engine
            // retry from every character of a long word run, and a log full of
            // base64 turns that into a stall. The lookbehind restricts the retries
            // to token starts; 64 covers "some.long.namespace.password".
            RedactionPattern.ofIgnoreCase(
                    "(?<![\\w.])(?:[\\w.]{0,64}password|passwd)\\s*[:=]\\s*[\"']?\\S{3,}[\"']?",
                    "password=[removed]",
                    "password", "passwd"));

    @Override
    public String key() {
        return "secret";
    }

    @Override
    protected List<RedactionPattern> patterns() {
        return PATTERNS;
    }
}
