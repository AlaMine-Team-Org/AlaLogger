package day.alacraft.alalogger.redact.rules;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EmailRuleTest {

    private final EmailRule rule = new EmailRule();

    private String redact(String content) {
        return rule.apply(content).content();
    }

    @Test
    void masksAnAddress() {
        assertEquals(
                "Licence check failed for [email-removed]",
                redact("Licence check failed for steve.smith@gmail.com"));
    }

    @Test
    void keepsExampleAddressesFromModMetadata() {
        // Printed on startup by the mod itself; it belongs to the author, not to
        // the person sharing the log.
        String log = "Contact: author@example.com";

        assertEquals(log, redact(log));
    }

    @Test
    void keepsModuleCoordinatesInStackFrames() {
        // Every modded stack trace is full of "@" and would be destroyed if the
        // domain did not have to end in letters.
        String log = String.join("\n",
                "\tat TRANSFORMER/minecraft@26.1.2/net.minecraft.client.main.Main.main(Main.java:205)",
                "\tat TRANSFORMER/neoforge@26.1.2.28-beta/net.neoforged.Foo.bar(Foo.java:12)",
                "\tat java.base@25.0.2/java.lang.Thread.run(Thread.java:1583)");

        assertEquals(log, redact(log));
    }
}
