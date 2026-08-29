package day.alacraft.alalogger.redact.rules;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SeedRuleTest {

    private final SeedRule rule = new SeedRule();

    private String redact(String content) {
        return rule.apply(content).content();
    }

    @Test
    void masksTheWorldSeed() {
        assertEquals(
                "\tWorld Seed: ********",
                redact("\tWorld Seed: 7026191857309640518"));
    }

    @Test
    void masksTheSignToo() {
        // Seeds are signed 64-bit. Leaving the minus behind would halve the search
        // space for anyone reversing it.
        assertEquals("World Seed: ********", redact("World Seed: -4823910384710293"));
    }

    @Test
    void keepsProseThatMerelyMentionsASeed() {
        String log = "[Server thread/INFO]: Preparing spawn area for the world seed picker";

        assertEquals(log, redact(log));
    }
}
