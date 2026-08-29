package day.alacraft.alalogger.redact.rules;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthTokenRuleTest {

    /** Shaped like a real Microsoft session token: header.payload.signature. */
    private static final String JWT =
            "eyJhbGciOiJIUzI1NiJ9.eyJ4dWlkIjoiMjUzNTQxMjM0NTY3ODkwMSJ9.dBjftJeZ4CVPmB92K27uhbUJU1p1r_wW1gFWFOEjXk";

    private final AuthTokenRule rule = new AuthTokenRule();

    private String redact(String content) {
        return rule.apply(content).content();
    }

    @Test
    void masksTheAccessTokenWhateverSeparatorTheLauncherUsed() {
        // Space, "=" and the comma-joined argument-array form all occur in the
        // wild, depending on the launcher and its version.
        assertEquals("--accessToken ****", redact("--accessToken " + JWT));
        assertEquals("--accessToken ****", redact("--accessToken=" + JWT));
        assertEquals("--accessToken ****", redact("--accessToken, " + JWT));
    }

    @Test
    void masksBothCopiesInAJvmCrashFile() {
        // hs_err prints the command line twice, in sections that look nothing
        // alike. A context-aware rule would clean one and publish the other.
        String log = String.join("\n",
                "Command Line: -Xmx4G net.minecraft.client.main.Main --accessToken " + JWT + " --uuid 069a79f4",
                "…",
                "java_command: net.minecraft.client.main.Main --accessToken " + JWT + " --xuid 2535412345678901");

        String clean = redact(log);

        assertFalse(clean.contains("eyJ"), clean);
        assertEquals(2, clean.split("--accessToken \\*\\*\\*\\*", -1).length - 1);
    }

    @Test
    void masksTheOtherAccountIdentifiers() {
        assertEquals("--xuid ****", redact("--xuid 2535412345678901"));
        assertEquals("--clientId ****", redact("--clientId MTIzNDU2Nzg5MDEyMzQ1Njc4OTA="));
        assertEquals("--session ****", redact("--session token:abc123:069a79f4"));
    }

    @Test
    void masksTheLegacySessionIdLine() {
        assertEquals(
                "(Session ID is token:****:****)",
                redact("(Session ID is token:abcdef123456:069a79f4-a1b2-c3d4)"));
    }

    @Test
    void masksLabelledTokensInJson() {
        assertEquals(
                "{\"accessToken\":\"****\",\"refreshToken\":\"****\"}",
                redact("{\"accessToken\": \"abc123def456\",\"refreshToken\": \"zzz\"}"));
    }

    @Test
    void masksAnUnlabelledTokenAnywhere() {
        // The reason the JWT signature is matched on sight: an auth plugin will
        // print a bare token mid-sentence and no flag name is there to key on.
        assertTrue(redact("Auth failed, sent " + JWT + " to the session server")
                .contains("[token-removed]"));

        assertEquals("Authorization: Bearer ****", redact("Authorization: Bearer abcdefghijklmnop"));
    }

    @Test
    void keepsTheThingsThatAreNotCredentials() {
        // The nickname and the UUID are public — Mojang hands out the UUID for any
        // name — and both are needed to read the log.
        String log = "--username Ma3auka --version 26.2 --uuid 069a79f4a1b2c3d4e5f6";

        assertEquals(log, redact(log));
    }

    @Test
    void doesNotSwallowTheFollowingLine() {
        // The separator class excludes \n on purpose: a line that merely ends on
        // the flag name would otherwise eat the first word of the next line and
        // collapse the newline, corrupting the log to protect nothing.
        String log = "arguments were --accessToken\nStarting minecraft server version 26.2";

        assertEquals(log, redact(log));
    }
}
