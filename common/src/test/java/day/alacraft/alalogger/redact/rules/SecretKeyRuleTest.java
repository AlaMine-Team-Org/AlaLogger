package day.alacraft.alalogger.redact.rules;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SecretKeyRuleTest {

    private final SecretKeyRule rule = new SecretKeyRule();

    private String redact(String content) {
        return rule.apply(content).content();
    }

    @Test
    void masksADiscordWebhook() {
        // The URL *is* the credential — anyone holding it can post as the server.
        assertEquals(
                "[chat-bridge] posting to [discord-webhook-removed]",
                redact("[chat-bridge] posting to https://discord.com/api/webhooks/"
                        + "123456789012345678" + "/" + "aBcDeFgHiJkLmNoPqRsTuV"));
    }

    /**
     * Assembled at runtime, like the Telegram one below. A literal of this shape
     * is indistinguishable from a real credential to a secret scanner, and this
     * file is published — GitHub's push protection rejected the whole repository
     * over it. The test cares about the shape, not the characters.
     */
    @Test
    void masksADiscordBotToken() {
        String token = "MTIzNDU2Nzg5MDEyMzQ1Njc4OTA" + "." + "GaBcDe" + "." + "FgHiJkLmNoPqRsTuVwXyZ012345";

        assertEquals("token=[discord-token-removed]", redact("token=" + token));
    }

    @Test
    void masksATelegramBotToken() {
        String token = "123456789:" + "A".repeat(35);

        assertEquals("bot [telegram-token-removed] ready", redact("bot " + token + " ready"));
    }

    @Test
    void masksAwsCredentials() {
        assertEquals("[aws-key-removed]", redact("AKIAIOSFODNN7EXAMPLE"));
        assertEquals(
                "aws_secret_access_key=[removed]",
                redact("aws_secret_access_key = wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"));
    }

    @Test
    void masksLabelledApiKeys() {
        assertEquals("api_key=[removed]", redact("api_key: \"abcdef1234567890abcdef\""));
        assertEquals("api_key=[removed]", redact("client-secret=0123456789abcdef0123"));
    }

    @Test
    void masksAPasswordCarryingAPrefix() {
        // The case that motivated the bounded prefix: a plain \bpassword never
        // matched "proxyPassword", and -Dhttp.proxyPassword is exactly what a
        // crash report prints back at you under JVM Flags.
        assertEquals("-password=[removed]", redact("-Dhttp.proxyPassword=hunter2secret"));
        assertEquals("password=[removed]", redact("rcon.password=letmein"));
    }

    @Test
    void keepsThePasswordWordWhenThereIsNoValue() {
        String log = "[Server thread/INFO]: Incorrect password for user Steve";

        assertEquals(log, redact(log));
    }

    @Test
    void keepsManifestHashesAndUuids() {
        // Both are long, both look secret-ish, and both are load-bearing for
        // diagnosis. Taken from a real 26.x crash report.
        String log = String.join("\n",
                "\t\talaloot-26.1.2-1.2.0.jar |AlaLoot |alaloot |1.2.0 "
                        + "|Manifest: fe6d8f0df30db96d6feebd335c3f76cee7ce517fe279847d69b474127c8aba4a",
                "\tCrash Report UUID: fd456361-7ee9-48c2-8048-9f276a67043a");

        assertEquals(log, redact(log));
    }

    /**
     * The self-inflicted case: a self-hosted instance configured with
     * credentials in the URL writes that password into latest.log, which is the
     * file this mod then uploads.
     */
    @Test
    void masksCredentialsInsideAUrl() {
        assertEquals(
                "uploading to https://[credentials-removed]@logs.example/api/v1",
                redact("uploading to https://admin:hunter2@logs.example/api/v1"));
    }

    @Test
    void leavesAnOrdinaryUrlAlone() {
        String line = "uploading to https://alacraft.day/api/v1";

        assertEquals(line, redact(line));
    }

    /** A port is not a password: host:8123 must survive. */
    @Test
    void leavesAHostWithAPortAlone() {
        String line = "uploading to http://127.0.0.1:8123/api/v1";

        assertEquals(line, redact(line));
    }

    /** Our own token, in the shape the site issues it. */
    @Test
    void masksThisModsApiToken() {
        String token = "42" + "|" + "a".repeat(40);

        assertEquals("apiToken=[api-token-removed]", redact("apiToken=" + token));
    }

    @Test
    void leavesShortPipeSeparatedValuesAlone() {
        String line = "tps=19|20 players=3|10";

        assertEquals(line, redact(line));
    }
}
